package com.feedback.analyzer.service.impl;

import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.FeedbackClaimStatusEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.model.vo.DashboardVO;
import com.feedback.analyzer.repository.FeedbackAnalyzedRepository;
import com.feedback.analyzer.repository.FeedbackClaimRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import com.feedback.analyzer.service.DashboardService;
import com.feedback.analyzer.service.PraiseAggregationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private static final int BUG_RECENT_WARNING_HOURS = 24;
    private static final int BUG_RECENT_WARNING_THRESHOLD = 10;
    private static final List<IssueStatusEnum> ARCHIVED_STATUSES = List.of(IssueStatusEnum.MERGED, IssueStatusEnum.CLOSED);
    private static final List<String> URGENT_BUG_SEVERITIES = List.of("CRITICAL", "HIGH");

    private final FeedbackRawRepository rawRepo;
    private final FeedbackAnalyzedRepository analyzedRepo;
    private final FeedbackIssueRepository issueRepo;
    private final FeedbackClaimRepository claimRepo;
    private final PraiseAggregationService praiseAggregationService;

    @Override
    public DashboardVO getOverview(Long productId, LocalDateTime start, LocalDateTime end) {
        if (start == null) {
            start = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        }
        if (end == null) {
            end = LocalDateTime.now();
        }

        long todayCount = rawRepo.countByProductIdAndCreatedAtBetween(productId,
                LocalDate.now().atStartOfDay(), LocalDate.now().atTime(LocalTime.MAX));
        long weekCount = rawRepo.countByProductIdAndCreatedAtBetween(productId, start, end);
        long totalCount = rawRepo.countByProductId(productId);

        long openIssueCount = issueRepo.searchIssues(productId, null, IssueStatusEnum.OPEN, null, null,
                PageRequest.of(0, 1)).getTotalElements();

        List<Map<String, Object>> categoryBreakdown = convertCountResults(
                analyzedRepo.countGroupByCategory(productId, start, end), "category");
        List<Map<String, Object>> moduleDistribution = convertCountResults(
                analyzedRepo.countGroupByModule(productId, start, end), "module");
        List<Map<String, Object>> channelDistribution = convertCountResults(
                rawRepo.countGroupByChannel(productId, start, end), "channel");

        List<Map<String, Object>> topIssues = issueRepo.findTopIssuesByProductId(productId, ARCHIVED_STATUSES, PageRequest.of(0, 10))
                .stream()
                .map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", i.getId());
                    m.put("title", i.getTitle());
                    m.put("reportCount", i.getReportCount());
                    m.put("severity", displaySeverity(i.getCategory(), i.getSeverity()));
                    m.put("priority", displayPriority(i.getCategory(), i.getPriority()));
                    m.put("status", i.getStatus().getCode());
                    return m;
                })
                .collect(Collectors.toList());

        List<Map<String, Object>> dailyTrend = generateDailyTrend(productId, start, end);

        return DashboardVO.builder()
                .todayCount(todayCount)
                .weekCount(weekCount)
                .totalCount(totalCount)
                .openIssueCount(openIssueCount)
                .categoryBreakdown(categoryBreakdown)
                .moduleDistribution(moduleDistribution)
                .topIssues(topIssues)
                .channelDistribution(channelDistribution)
                .dailyTrend(dailyTrend)
                .rangeStart(start)
                .rangeEnd(end)
                .bugBoard(buildBugBoard(productId, start, end))
                .suggestionBoard(buildSuggestionBoard(productId, start, end))
                .praiseBoard(buildPraiseBoard(productId, start, end))
                .build();
    }

    private Map<String, Object> buildBugBoard(Long productId, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("claimCount", claimRepo.countByProductIdAndCategoryAndCreatedAtBetween(
                productId, FeedbackCategoryEnum.BUG, start, end));
        board.put("urgentIssues", buildRankedIssues(
                claimRepo.countUrgentIssueIdsByCategory(
                        productId,
                        FeedbackCategoryEnum.BUG,
                        URGENT_BUG_SEVERITIES,
                        ARCHIVED_STATUSES,
                        start,
                        end),
                start,
                end,
                10,
                this::isUrgentBugRow,
                urgentIssueComparator(),
                false));
        board.put("topIssues", buildTopIssuesByCategory(productId, FeedbackCategoryEnum.BUG, start, end, 10));
        board.put("recentIssues", issueRepo.findRecentNewIssues(
                        productId, FeedbackCategoryEnum.BUG, ARCHIVED_STATUSES, start, end)
                .stream()
                .map(issue -> issueRow(issue, start, end))
                .sorted(recentIssueComparator())
                .limit(8)
                .toList());
        board.put("moduleDistribution", convertCountResults(
                claimRepo.countModulesByCategory(productId, FeedbackCategoryEnum.BUG, start, end), "module"));
        return board;
    }

    private Map<String, Object> buildSuggestionBoard(Long productId, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("claimCount", claimRepo.countByProductIdAndCategoryAndCreatedAtBetween(
                productId, FeedbackCategoryEnum.SUGGESTION, start, end));
        board.put("topIssues", buildTopIssuesByCategory(productId, FeedbackCategoryEnum.SUGGESTION, start, end, 10));
        board.put("recentIssues", issueRepo.findRecentNewIssues(
                        productId, FeedbackCategoryEnum.SUGGESTION, ARCHIVED_STATUSES, start, end)
                .stream()
                .map(issue -> issueRow(issue, start, end))
                .sorted(recentIssueComparator())
                .limit(8)
                .toList());
        board.put("moduleDistribution", convertCountResults(
                claimRepo.countModulesByCategory(productId, FeedbackCategoryEnum.SUGGESTION, start, end), "module"));
        return board;
    }

    private Map<String, Object> buildPraiseBoard(Long productId, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("recordedCount", claimRepo.countByProductIdAndCategoryAndStatusAndCreatedAtBetween(
                productId, FeedbackCategoryEnum.PRAISE, FeedbackClaimStatusEnum.RECORDED, start, end));
        board.put("ignoredCount", claimRepo.countByProductIdAndCategoryAndStatusAndCreatedAtBetween(
                productId, FeedbackCategoryEnum.PRAISE, FeedbackClaimStatusEnum.IGNORED, start, end));
        board.put("moduleDistribution", convertCountResults(
                claimRepo.countModulesByCategoryAndStatus(
                        productId, FeedbackCategoryEnum.PRAISE, FeedbackClaimStatusEnum.RECORDED, start, end),
                "module"));
        board.put("highlights", praiseAggregationService.buildGroups(productId, start, end, 10));
        return board;
    }

    private List<Map<String, Object>> buildTopIssuesByCategory(Long productId,
                                                               FeedbackCategoryEnum category,
                                                               LocalDateTime start,
                                                               LocalDateTime end,
                                                               int limit) {
        return buildRankedIssues(
                claimRepo.countTopIssueIdsByCategory(productId, category, ARCHIVED_STATUSES, start, end),
                start,
                end,
                limit,
                item -> true,
                topIssueComparator(),
                true);
    }

    private Map<String, Object> issueRow(FeedbackIssue issue, LocalDateTime start, LocalDateTime end) {
        return issueRow(issue, start, end, null);
    }

    private Map<String, Object> issueRow(FeedbackIssue issue,
                                         LocalDateTime start,
                                         LocalDateTime end,
                                         Long weeklyFeedbackCountOverride) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", issue.getId());
        m.put("title", issue.getTitle());
        m.put("category", issue.getCategory() != null ? issue.getCategory().name() : "");
        m.put("categoryLabel", issue.getCategory() != null ? issue.getCategory().getLabel() : "");
        m.put("module", issue.getModule());
        m.put("severity", displaySeverity(issue.getCategory(), issue.getSeverity()));
        m.put("priority", displayPriority(issue.getCategory(), issue.getPriority()));
        m.put("status", issue.getStatus() != null ? issue.getStatus().getCode() : "");
        m.put("statusLabel", issue.getStatus() != null ? issue.getStatus().getLabel() : "");
        m.put("confirmed", Boolean.TRUE.equals(issue.getConfirmed()));
        m.put("confirmedLabel", Boolean.TRUE.equals(issue.getConfirmed()) ? "已确认" : "未确认");
        m.put("reportCount", issue.getReportCount() != null ? issue.getReportCount() : 0);
        long weeklyFeedbackCount = weeklyFeedbackCountOverride != null
                ? weeklyFeedbackCountOverride
                : claimRepo.countDistinctRawIdByIssueIdAndCreatedAtBetween(issue.getId(), start, end);
        m.put("weeklyFeedbackCount", weeklyFeedbackCount);
        long recentCount = recentCount(issue, BUG_RECENT_WARNING_HOURS);
        m.put("recentCountIn24h", recentCount);
        m.put("warningLabels", warningLabels(issue, recentCount));
        m.put("firstReportAt", issue.getFirstReportAt());
        m.put("latestReportAt", issue.getLatestReportAt());
        return m;
    }

    private List<Map<String, Object>> buildRankedIssues(List<Object[]> issueCounts,
                                                        LocalDateTime start,
                                                        LocalDateTime end,
                                                        int limit,
                                                        Predicate<Map<String, Object>> filter,
                                                        Comparator<Map<String, Object>> comparator,
                                                        boolean includeWindowCount) {
        return issueCounts.stream()
                .map(row -> rankedIssueRow(row, start, end, includeWindowCount))
                .filter(item -> item != null)
                .filter(filter)
                .sorted(comparator)
                .limit(limit)
                .toList();
    }

    private Map<String, Object> rankedIssueRow(Object[] row,
                                               LocalDateTime start,
                                               LocalDateTime end,
                                               boolean includeWindowCount) {
        String issueId = row[0] != null ? row[0].toString() : "";
        long windowCount = row[1] instanceof Number number ? number.longValue() : 0L;
        FeedbackIssue issue = issueRepo.findById(issueId).orElse(null);
        if (issue == null || issue.getStatus() == null || issue.getStatus().isArchivedStatus()) {
            return null;
        }
        Map<String, Object> item = issueRow(issue, start, end, windowCount);
        if (includeWindowCount) {
            item.put("windowCount", windowCount);
        }
        return item;
    }

    private Comparator<Map<String, Object>> urgentIssueComparator() {
        return Comparator
                .comparingInt((Map<String, Object> item) -> severityRank((String) item.get("severity")))
                .thenComparingLong(item -> -longValue(item.get("weeklyFeedbackCount")))
                .thenComparingLong(item -> -longValue(item.get("reportCount")))
                .thenComparing(item -> timeValue(item.get("latestReportAt")), Comparator.reverseOrder());
    }

    private Comparator<Map<String, Object>> topIssueComparator() {
        return Comparator
                .comparingLong((Map<String, Object> item) -> longValue(item.get("windowCount")))
                .reversed()
                .thenComparing(Comparator.comparingLong((Map<String, Object> item) -> longValue(item.get("reportCount"))).reversed())
                .thenComparing(item -> timeValue(item.get("latestReportAt")), Comparator.reverseOrder());
    }

    private Comparator<Map<String, Object>> recentIssueComparator() {
        return Comparator
                .comparing((Map<String, Object> item) -> timeValue(item.get("firstReportAt")), Comparator.reverseOrder())
                .thenComparingInt(item -> severityRank((String) item.get("severity")))
                .thenComparingLong(item -> -longValue(item.get("reportCount")))
                .thenComparing(item -> timeValue(item.get("latestReportAt")), Comparator.reverseOrder());
    }

    private boolean isUrgentBugRow(Map<String, Object> item) {
        return URGENT_BUG_SEVERITIES.contains(item.get("severity"));
    }

    private int severityRank(String severity) {
        if ("CRITICAL".equalsIgnoreCase(severity)) {
            return 0;
        }
        if ("HIGH".equalsIgnoreCase(severity)) {
            return 1;
        }
        if ("MEDIUM".equalsIgnoreCase(severity)) {
            return 2;
        }
        if ("LOW".equalsIgnoreCase(severity)) {
            return 3;
        }
        return 4;
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private LocalDateTime timeValue(Object value) {
        return value instanceof LocalDateTime time ? time : LocalDateTime.MIN;
    }

    private List<Map<String, Object>> convertCountResults(List<Object[]> results, String nameKey) {
        return results.stream()
                .map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put(nameKey, translateCategory(row[0]));
                    m.put("count", row[1]);
                    return m;
                })
                .collect(Collectors.toList());
    }

    private Object translateCategory(Object value) {
        if (value == null) return "";
        String text = value.toString().trim();
        String upper = text.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "BUG", "缺陷", "故障", "问题" -> FeedbackCategoryEnum.BUG.getLabel();
            case "PRAISE", "好评", "表扬" -> FeedbackCategoryEnum.PRAISE.getLabel();
            case "SUGGESTION", "需求", "建议", "优化", "改进" -> FeedbackCategoryEnum.SUGGESTION.getLabel();
            default -> {
                try {
                    yield FeedbackCategoryEnum.valueOf(upper).getLabel();
                } catch (IllegalArgumentException e) {
                    yield value;
                }
            }
        };
    }

    private String displaySeverity(FeedbackCategoryEnum category, String severity) {
        return category == FeedbackCategoryEnum.BUG ? severity : null;
    }

    private String displayPriority(FeedbackCategoryEnum category, String priority) {
        return category == FeedbackCategoryEnum.BUG ? priority : null;
    }

    private long recentCount(FeedbackIssue issue, int hours) {
        if (issue.getCategory() != FeedbackCategoryEnum.BUG) {
            return 0;
        }
        LocalDateTime end = LocalDateTime.now();
        return analyzedRepo.countByIssueIdAndAnalyzedAtBetween(issue.getId(), end.minusHours(hours), end);
    }

    private List<String> warningLabels(FeedbackIssue issue, long recentCount) {
        if (issue.getCategory() != FeedbackCategoryEnum.BUG) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        if (recentCount >= BUG_RECENT_WARNING_THRESHOLD) {
            labels.add("24小时新增较多");
        }
        return labels;
    }

    private List<Map<String, Object>> generateDailyTrend(Long productId, LocalDateTime start, LocalDateTime end) {
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate current = start.toLocalDate();
        LocalDate endDate = end.toLocalDate();
        while (!current.isAfter(endDate)) {
            LocalDateTime dayStart = current.atStartOfDay();
            LocalDateTime dayEnd = current.atTime(LocalTime.MAX);
            long count = rawRepo.countByProductIdAndCreatedAtBetween(productId, dayStart, dayEnd);
            trend.add(Map.of("date", current.toString(), "count", count));
            current = current.plusDays(1);
        }
        return trend;
    }
}
