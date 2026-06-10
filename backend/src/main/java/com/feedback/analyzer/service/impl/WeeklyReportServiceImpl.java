package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.entity.Product;
import com.feedback.analyzer.entity.WeeklyReport;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.model.vo.DashboardVO;
import com.feedback.analyzer.model.vo.WeeklyReportVO;
import com.feedback.analyzer.repository.FeedbackClaimRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import com.feedback.analyzer.repository.ProductRepository;
import com.feedback.analyzer.repository.WeeklyReportRepository;
import com.feedback.analyzer.service.DashboardService;
import com.feedback.analyzer.service.NotificationService;
import com.feedback.analyzer.service.WeeklyReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyReportServiceImpl implements WeeklyReportService {

    private static final List<IssueStatusEnum> EXCLUDED_ISSUE_STATUSES = List.of(IssueStatusEnum.MERGED, IssueStatusEnum.CLOSED);

    private final WeeklyReportRepository reportRepo;
    private final FeedbackRawRepository rawRepo;
    private final FeedbackClaimRepository claimRepo;
    private final FeedbackIssueRepository issueRepo;
    private final ProductRepository productRepo;
    private final NotificationService notificationService;
    private final DashboardService dashboardService;
    private final ObjectMapper objectMapper;
    private final RedissonClient redisson;

    @Override
    @Transactional
    public WeeklyReportVO generate(Long productId, String weekStartStr) {
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

        LocalDate weekStart = weekStartStr != null && !weekStartStr.isBlank()
                ? LocalDate.parse(weekStartStr)
                : LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);
        LocalDateTime startTime = weekStart.atStartOfDay();
        LocalDateTime endTime = weekEnd.atTime(LocalTime.MAX);
        String weekRange = weekStart + " ~ " + weekEnd;

        DashboardVO dashboard = dashboardService.getOverview(productId, startTime, endTime);
        Map<String, Object> reportData = buildReportData(product, weekRange, startTime, endTime, dashboard);
        String reportDataJson = serializeReportData(reportData);
        String reportText = renderReportMarkdown(reportData);

        WeeklyReport report = reportRepo.findByProductIdAndWeekStart(productId, weekStart)
                .orElseGet(() -> WeeklyReport.builder()
                        .productId(productId)
                        .weekStart(weekStart)
                        .isSent(false)
                        .build());

        report.setWeekEnd(weekEnd);
        report.setContent(reportText);
        report.setRawData(reportDataJson);
        report.setGeneratedAt(LocalDateTime.now());
        report.setIsSent(false);
        report.setSentAt(null);
        report = reportRepo.save(report);

        return toWeeklyReportVO(report, product.getName());
    }

    @Override
    public List<WeeklyReportVO> list(Long productId) {
        String productName = loadProductName(productId);
        return reportRepo.findByProductIdOrderByWeekStartDesc(productId)
                .stream()
                .map(report -> toWeeklyReportVO(report, productName))
                .toList();
    }

    @Override
    public WeeklyReportVO getById(Long id) {
        WeeklyReport report = reportRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Report not found: " + id));
        return toWeeklyReportVO(report, loadProductName(report.getProductId()));
    }

    @Override
    public void send(Long id) {
        RLock lock = redisson.getLock("weekly-report:send:" + id);
        boolean locked = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("周报正在推送中，请稍后刷新");
            }

            WeeklyReport report = reportRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Report not found: " + id));

            assertFeishuReady(report.getProductId());
            String weekRange = report.getWeekStart() + " ~ " + report.getWeekEnd();
            boolean sent = notificationService.notifyReportReady(
                    report.getProductId(), report.getId(), weekRange, report.getContent());
            if (!sent) {
                throw new IllegalStateException("飞书周报发送失败，请检查机器人配置或稍后重试");
            }

            report.setIsSent(true);
            report.setSentAt(LocalDateTime.now());
            reportRepo.saveAndFlush(report);
            log.info("Weekly report {} sent", id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("周报推送被中断，请稍后重试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void assertFeishuReady(Long productId) {
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));
        if (!"ENABLED".equals(product.getFeishuStatus())) {
            throw new IllegalStateException("请先在产品管理中启用飞书通知并配置机器人地址");
        }
    }

    private Map<String, Object> buildReportData(Product product,
                                                String weekRange,
                                                LocalDateTime startTime,
                                                LocalDateTime endTime,
                                                DashboardVO dashboard) {
        Map<String, Object> reportData = new LinkedHashMap<>();
        Map<String, Object> summaryMetrics = buildSummaryMetrics(product.getId(), startTime, endTime, dashboard);
        List<Map<String, Object>> bugAlerts = buildBugAlerts(product.getId(), startTime, endTime, 5);
        List<Map<String, Object>> suggestionHighlights = topRows(dashboard.getSuggestionBoard(), "topIssues", 5);
        List<Map<String, Object>> praiseHighlights = topRows(dashboard.getPraiseBoard(), "highlights", 5);
        List<String> improvementSuggestions = buildImprovementSuggestions(summaryMetrics, bugAlerts);

        reportData.put("productName", product.getName());
        reportData.put("weekRange", weekRange);
        reportData.put("summaryMetrics", summaryMetrics);
        reportData.put("bugAlerts", bugAlerts);
        reportData.put("suggestionHighlights", suggestionHighlights);
        reportData.put("praiseHighlights", praiseHighlights);
        reportData.put("improvementSuggestions", improvementSuggestions);
        return reportData;
    }

    private Map<String, Object> buildSummaryMetrics(Long productId,
                                                    LocalDateTime startTime,
                                                    LocalDateTime endTime,
                                                    DashboardVO dashboard) {
        long rawFeedbackCount = rawRepo.countByProductIdAndCreatedAtBetween(productId, startTime, endTime);
        long effectiveFeedbackCount = rawRepo.countByProductIdAndStatusAndCreatedAtBetween(
                productId, FeedbackStatusEnum.ANALYZED, startTime, endTime);
        long lowQualityFeedbackCount = rawRepo.countByProductIdAndStatusAndCreatedAtBetween(
                productId, FeedbackStatusEnum.LOW_QUALITY, startTime, endTime);
        long skippedFeedbackCount = rawRepo.countByProductIdAndStatusAndCreatedAtBetween(
                productId, FeedbackStatusEnum.SKIPPED, startTime, endTime);
        long claimCount = claimRepo.countByProductIdAndCreatedAtBetween(productId, startTime, endTime);
        long bugClaimCount = valueAsLong(dashboard.getBugBoard(), "claimCount");
        long suggestionClaimCount = valueAsLong(dashboard.getSuggestionBoard(), "claimCount");
        long recordedPraiseCount = valueAsLong(dashboard.getPraiseBoard(), "recordedCount");
        long ignoredPraiseCount = valueAsLong(dashboard.getPraiseBoard(), "ignoredCount");
        long activeBugIssueCount = issueRepo.countByProductIdAndCategoryAndLatestReportAtBetween(
                productId, FeedbackCategoryEnum.BUG, startTime, endTime);
        long newBugIssueCount = issueRepo.countByProductIdAndCategoryAndFirstReportAtBetween(
                productId, FeedbackCategoryEnum.BUG, startTime, endTime);
        long criticalActiveBugIssueCount = issueRepo.countByProductIdAndCategoryAndSeverityAndLatestReportAtBetween(
                productId, FeedbackCategoryEnum.BUG, "CRITICAL", startTime, endTime);
        long highActiveBugIssueCount = issueRepo.countByProductIdAndCategoryAndSeverityAndLatestReportAtBetween(
                productId, FeedbackCategoryEnum.BUG, "HIGH", startTime, endTime);
        long mediumActiveBugIssueCount = issueRepo.countByProductIdAndCategoryAndSeverityAndLatestReportAtBetween(
                productId, FeedbackCategoryEnum.BUG, "MEDIUM", startTime, endTime);
        long lowActiveBugIssueCount = issueRepo.countByProductIdAndCategoryAndSeverityAndLatestReportAtBetween(
                productId, FeedbackCategoryEnum.BUG, "LOW", startTime, endTime);

        Map<String, Object> summaryMetrics = new LinkedHashMap<>();
        summaryMetrics.put("rawFeedbackCount", rawFeedbackCount);
        summaryMetrics.put("effectiveFeedbackCount", effectiveFeedbackCount);
        summaryMetrics.put("lowQualityFeedbackCount", lowQualityFeedbackCount);
        summaryMetrics.put("skippedFeedbackCount", skippedFeedbackCount);
        summaryMetrics.put("claimCount", claimCount);
        summaryMetrics.put("bugClaimCount", bugClaimCount);
        summaryMetrics.put("suggestionClaimCount", suggestionClaimCount);
        summaryMetrics.put("recordedPraiseCount", recordedPraiseCount);
        summaryMetrics.put("ignoredPraiseCount", ignoredPraiseCount);
        summaryMetrics.put("activeBugIssueCount", activeBugIssueCount);
        summaryMetrics.put("newBugIssueCount", newBugIssueCount);
        summaryMetrics.put("criticalActiveBugIssueCount", criticalActiveBugIssueCount);
        summaryMetrics.put("highActiveBugIssueCount", highActiveBugIssueCount);
        summaryMetrics.put("mediumActiveBugIssueCount", mediumActiveBugIssueCount);
        summaryMetrics.put("lowActiveBugIssueCount", lowActiveBugIssueCount);
        return summaryMetrics;
    }

    private List<Map<String, Object>> buildBugAlerts(Long productId,
                                                     LocalDateTime startTime,
                                                     LocalDateTime endTime,
                                                     int limit) {
        List<FeedbackIssue> issues = issueRepo.findUrgentIssues(
                productId,
                FeedbackCategoryEnum.BUG,
                EXCLUDED_ISSUE_STATUSES,
                startTime,
                endTime,
                PageRequest.of(0, Math.max(limit * 4, 20)));
        return issues.stream()
                .map(issue -> toBugAlert(issue, startTime, endTime))
                .sorted(bugAlertComparator())
                .limit(limit)
                .toList();
    }

    private Map<String, Object> toBugAlert(FeedbackIssue issue,
                                           LocalDateTime startTime,
                                           LocalDateTime endTime) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", issue.getId());
        item.put("title", issue.getTitle());
        item.put("severity", issue.getSeverity());
        item.put("priority", issue.getPriority());
        item.put("status", issue.getStatus() != null ? issue.getStatus().getCode() : "");
        item.put("statusLabel", issue.getStatus() != null ? issue.getStatus().getLabel() : "");
        item.put("reportCount", issue.getReportCount() == null ? 0 : issue.getReportCount());
        item.put("latestReportAt", issue.getLatestReportAt());
        item.put("weeklyFeedbackCount", claimRepo.countDistinctRawIdByIssueIdAndCreatedAtBetween(
                issue.getId(), startTime, endTime));
        return item;
    }

    private Comparator<Map<String, Object>> bugAlertComparator() {
        return Comparator
                .comparingInt((Map<String, Object> item) -> severityRank(stringValue(item.get("severity"))))
                .thenComparingLong(item -> -asLong(item.get("weeklyFeedbackCount")))
                .thenComparingLong(item -> -asLong(item.get("reportCount")))
                .thenComparing(item -> (LocalDateTime) item.get("latestReportAt"),
                        Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private int severityRank(String severity) {
        return switch (severity == null ? "" : severity.trim().toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }

    private List<Map<String, Object>> topRows(Map<String, Object> board, String key, int limit) {
        if (board == null) {
            return List.of();
        }
        Object value = board.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> source) {
                rows.add(toStringKeyMap(source));
            }
            if (rows.size() >= limit) {
                break;
            }
        }
        return rows;
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> source) {
        Map<String, Object> target = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            target.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return target;
    }

    private List<String> buildImprovementSuggestions(Map<String, Object> summaryMetrics,
                                                     List<Map<String, Object>> bugAlerts) {
        List<String> suggestions = new ArrayList<>();
        long rawFeedbackCount = asLong(summaryMetrics.get("rawFeedbackCount"));
        long lowQualityFeedbackCount = asLong(summaryMetrics.get("lowQualityFeedbackCount"));
        long skippedFeedbackCount = asLong(summaryMetrics.get("skippedFeedbackCount"));
        long criticalActiveBugIssueCount = asLong(summaryMetrics.get("criticalActiveBugIssueCount"));

        if (criticalActiveBugIssueCount > 0 && !bugAlerts.isEmpty()) {
            String titles = bugAlerts.stream()
                    .filter(item -> "CRITICAL".equalsIgnoreCase(stringValue(item.get("severity"))))
                    .map(item -> stringValue(item.get("title")))
                    .filter(title -> !title.isBlank())
                    .limit(2)
                    .collect(Collectors.joining("、"));
            if (!titles.isBlank()) {
                suggestions.add("本周存在 " + criticalActiveBugIssueCount + " 个 CRITICAL 活跃问题，建议优先复现并跟踪：" + titles + "。");
            } else {
                suggestions.add("本周存在 " + criticalActiveBugIssueCount + " 个 CRITICAL 活跃问题，建议优先安排复现和根因定位。");
            }
        }

        if (rawFeedbackCount > 0) {
            double lowQualityRatio = (double) lowQualityFeedbackCount / rawFeedbackCount;
            if (lowQualityRatio >= 0.4d) {
                suggestions.add("本周低质量反馈占比为 " + percentage(lowQualityRatio) + "，建议复核反馈入口和过滤规则，避免把有效问题挡在外面。");
            }

        }

        if (suggestions.isEmpty()) {
            suggestions.add("本周暂无额外改进建议。");
        }
        return suggestions.stream().limit(3).toList();
    }

    private String renderReportMarkdown(Map<String, Object> reportData) {
        String productName = stringValue(reportData.get("productName"));
        String weekRange = stringValue(reportData.get("weekRange"));
        Map<String, Object> summaryMetrics = nestedMap(reportData.get("summaryMetrics"));
        List<Map<String, Object>> bugAlerts = nestedList(reportData.get("bugAlerts"));
        List<Map<String, Object>> suggestionHighlights = nestedList(reportData.get("suggestionHighlights"));
        List<Map<String, Object>> praiseHighlights = nestedList(reportData.get("praiseHighlights"));
        List<String> improvementSuggestions = stringList(reportData.get("improvementSuggestions"));

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(productName).append(' ').append(weekRange).append(" 周报\n\n");

        sb.append("## 一、本周核心数据\n");
        sb.append("- 接入原始反馈：").append(asLong(summaryMetrics.get("rawFeedbackCount"))).append(" 条\n");
        sb.append("- 有效反馈：").append(asLong(summaryMetrics.get("effectiveFeedbackCount"))).append(" 条\n");
        sb.append("- 低质量反馈：").append(asLong(summaryMetrics.get("lowQualityFeedbackCount"))).append(" 条\n");
        sb.append("- 拆分反馈片段：").append(asLong(summaryMetrics.get("claimCount"))).append(" 个\n");
        sb.append("- Bug 片段：").append(asLong(summaryMetrics.get("bugClaimCount"))).append(" 个；建议片段：")
                .append(asLong(summaryMetrics.get("suggestionClaimCount"))).append(" 个；有效好评：")
                .append(asLong(summaryMetrics.get("recordedPraiseCount"))).append(" 条\n");
        sb.append("- 本周活跃 Bug 问题：").append(asLong(summaryMetrics.get("activeBugIssueCount")))
                .append(" 个，其中 CRITICAL ").append(asLong(summaryMetrics.get("criticalActiveBugIssueCount")))
                .append(" 个、HIGH ").append(asLong(summaryMetrics.get("highActiveBugIssueCount")))
                .append(" 个、MEDIUM ").append(asLong(summaryMetrics.get("mediumActiveBugIssueCount")))
                .append(" 个、LOW ").append(asLong(summaryMetrics.get("lowActiveBugIssueCount"))).append(" 个\n");
        sb.append("- 本周新建 Bug 问题：").append(asLong(summaryMetrics.get("newBugIssueCount"))).append(" 个\n\n");

        sb.append("## 二、Bug 预警 Top5\n");
        if (bugAlerts.isEmpty()) {
            sb.append("本周无活跃 Bug。\n\n");
        } else {
            for (Map<String, Object> item : bugAlerts) {
                sb.append("- ")
                        .append(defaultIfBlank(stringValue(item.get("severity")), "-")).append(" / ")
                        .append(defaultIfBlank(stringValue(item.get("priority")), "-")).append(" / ")
                        .append(defaultIfBlank(stringValue(item.get("statusLabel")), defaultIfBlank(stringValue(item.get("status")), "-")))
                        .append("：")
                        .append(defaultIfBlank(stringValue(item.get("title")), "未命名问题"))
                        .append("（本周新增反馈：").append(asLong(item.get("weeklyFeedbackCount")))
                        .append("，累计反馈：").append(asLong(item.get("reportCount")))
                        .append("）\n");
            }
            sb.append('\n');
        }

        sb.append("## 三、建议聚合 Top5\n");
        if (suggestionHighlights.isEmpty()) {
            sb.append("本周无明确建议。\n\n");
        } else {
            for (Map<String, Object> item : suggestionHighlights) {
                sb.append("- ")
                        .append(defaultIfBlank(stringValue(item.get("title")), "未命名建议"))
                        .append("（本周新增反馈：").append(asLong(item.get("windowCount")))
                        .append("，累计反馈：").append(asLong(item.get("reportCount")))
                        .append("）\n");
            }
            sb.append('\n');
        }

        sb.append("## 四、好评亮点 Top5\n");
        if (praiseHighlights.isEmpty()) {
            sb.append("本周无明确好评亮点。\n\n");
        } else {
            for (Map<String, Object> item : praiseHighlights) {
                String module = defaultIfBlank(stringValue(item.get("module")), "未分类对象");
                String summary = defaultIfBlank(
                        stringValue(item.get("representativeSummary")),
                        defaultIfBlank(stringValue(item.get("summary")), "无摘要"));
                sb.append("- ").append(module).append("：")
                        .append(summary)
                        .append("（累计好评：").append(asLong(item.get("count"))).append("）\n");
            }
            sb.append('\n');
        }

        sb.append("## 五、改进建议\n");
        if (improvementSuggestions.isEmpty()) {
            sb.append("1. 本周暂无额外改进建议。\n");
        } else {
            for (int i = 0; i < improvementSuggestions.size(); i++) {
                sb.append(i + 1).append(". ").append(improvementSuggestions.get(i)).append('\n');
            }
        }

        return sb.toString().trim();
    }

    private String serializeReportData(Map<String, Object> reportData) {
        try {
            return objectMapper.writeValueAsString(normalizeForJson(reportData));
        } catch (Exception e) {
            log.error("Failed to serialize weekly report data", e);
            return reportData.toString();
        }
    }

    private WeeklyReportVO toWeeklyReportVO(WeeklyReport report, String productName) {
        return WeeklyReportVO.builder()
                .id(report.getId())
                .productName(productName)
                .weekStart(report.getWeekStart())
                .weekEnd(report.getWeekEnd())
                .content(report.getContent())
                .isSent(report.getIsSent())
                .sentAt(report.getSentAt())
                .generatedAt(report.getGeneratedAt())
                .build();
    }

    private String loadProductName(Long productId) {
        return productRepo.findById(productId)
                .map(Product::getName)
                .orElse("未命名产品");
    }

    private Map<String, Object> nestedMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return toStringKeyMap(map);
        }
        return Map.of();
    }

    private List<Map<String, Object>> nestedList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add(toStringKeyMap(map));
            }
        }
        return rows;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::stringValue).toList();
    }

    private long valueAsLong(Map<String, Object> map, String key) {
        if (map == null) {
            return 0L;
        }
        return asLong(map.get(key));
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String percentage(double ratio) {
        return String.format(Locale.ROOT, "%.0f%%", ratio * 100.0d);
    }

    private Object normalizeForJson(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalizeForJson(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::normalizeForJson).toList();
        }
        return value;
    }
}
