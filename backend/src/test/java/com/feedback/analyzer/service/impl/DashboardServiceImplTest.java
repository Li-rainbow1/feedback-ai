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
import com.feedback.analyzer.service.PraiseAggregationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardServiceImplTest {

    @Test
    void separatesBugBoardsByUrgencyFrequencyAndRecency() {
        Dependencies deps = dependencies();
        LocalDateTime start = LocalDateTime.of(2026, 6, 2, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 8, 23, 59);

        FeedbackIssue critical = issue("ISSUE-C", "支付崩溃", "支付", "CRITICAL", "P1",
                3, LocalDateTime.of(2026, 6, 4, 9, 0), LocalDateTime.of(2026, 6, 8, 18, 0));
        FeedbackIssue highHeavy = issue("ISSUE-H1", "CG 卡死", "CG播放", "HIGH", "P2",
                6, LocalDateTime.of(2026, 6, 8, 12, 0), LocalDateTime.of(2026, 6, 8, 21, 0));
        FeedbackIssue highLight = issue("ISSUE-H2", "启动失败", "启动", "HIGH", "P2",
                4, LocalDateTime.of(2026, 6, 6, 8, 0), LocalDateTime.of(2026, 6, 8, 19, 0));
        FeedbackIssue medium = issue("ISSUE-M", "空气墙", "场景", "MEDIUM", "P3",
                7, LocalDateTime.of(2026, 6, 8, 12, 0), LocalDateTime.of(2026, 6, 8, 20, 0));
        FeedbackIssue low = issue("ISSUE-L", "文案错字", "界面", "LOW", "P4",
                1, LocalDateTime.of(2026, 6, 7, 8, 0), LocalDateTime.of(2026, 6, 8, 17, 0));

        Map<String, FeedbackIssue> issues = Map.of(
                critical.getId(), critical,
                highHeavy.getId(), highHeavy,
                highLight.getId(), highLight,
                medium.getId(), medium,
                low.getId(), low
        );

        stubOverviewDefaults(deps, start, end);
        when(deps.issueRepo.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(issues.get(invocation.getArgument(0, String.class))));
        when(deps.claimRepo.countUrgentIssueIdsByCategory(
                eq(1L),
                eq(FeedbackCategoryEnum.BUG),
                eq(List.of("CRITICAL", "HIGH")),
                eq(List.of(IssueStatusEnum.MERGED, IssueStatusEnum.CLOSED)),
                eq(start),
                eq(end)))
                .thenReturn(List.of(
                        row(highLight.getId(), 2),
                        row(medium.getId(), 9),
                        row(critical.getId(), 1),
                        row(highHeavy.getId(), 2)
                ));
        when(deps.claimRepo.countTopIssueIdsByCategory(
                eq(1L),
                eq(FeedbackCategoryEnum.BUG),
                eq(List.of(IssueStatusEnum.MERGED, IssueStatusEnum.CLOSED)),
                eq(start),
                eq(end)))
                .thenReturn(List.of(
                        row(low.getId(), 1),
                        row(highLight.getId(), 2),
                        row(highHeavy.getId(), 2),
                        row(medium.getId(), 2)
                ));
        when(deps.issueRepo.findRecentNewIssues(
                eq(1L),
                eq(FeedbackCategoryEnum.BUG),
                eq(List.of(IssueStatusEnum.MERGED, IssueStatusEnum.CLOSED)),
                eq(start),
                eq(end)))
                .thenReturn(List.of(highLight, critical, medium, highHeavy, low));
        when(deps.claimRepo.countDistinctRawIdByIssueIdAndCreatedAtBetween(anyString(), eq(start), eq(end)))
                .thenReturn(1L);

        DashboardVO overview = deps.service.getOverview(1L, start, end);
        Map<String, Object> bugBoard = overview.getBugBoard();

        List<String> urgentTitles = titles(bugBoard.get("urgentIssues"));
        assertThat(urgentTitles).containsExactly("支付崩溃", "CG 卡死", "启动失败");
        assertThat(severities(bugBoard.get("urgentIssues"))).containsOnly("CRITICAL", "HIGH");

        List<Map<String, Object>> topIssues = rows(bugBoard.get("topIssues"));
        assertThat(titles(topIssues)).containsExactly("空气墙", "CG 卡死", "启动失败", "文案错字");
        assertThat(topIssues).extracting(row -> row.get("windowCount")).containsExactly(2L, 2L, 2L, 1L);
        assertThat(topIssues).extracting(row -> row.get("severity")).containsExactly("MEDIUM", "HIGH", "HIGH", "LOW");

        List<String> recentTitles = titles(bugBoard.get("recentIssues"));
        assertThat(recentTitles).containsExactly("CG 卡死", "空气墙", "文案错字", "启动失败", "支付崩溃");

        verify(deps.claimRepo).countUrgentIssueIdsByCategory(
                1L,
                FeedbackCategoryEnum.BUG,
                List.of("CRITICAL", "HIGH"),
                List.of(IssueStatusEnum.MERGED, IssueStatusEnum.CLOSED),
                start,
                end);
    }

    @Test
    void usesPraiseAggregationServiceForHighlights() {
        Dependencies deps = dependencies();
        LocalDateTime start = LocalDateTime.of(2026, 6, 2, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 8, 23, 59);

        stubOverviewDefaults(deps, start, end);
        when(deps.praiseAggregationService.buildGroups(1L, start, end, 10))
                .thenReturn(List.of(
                        Map.of(
                                "groupId", "G1",
                                "module", "战斗系统",
                                "count", 2,
                                "representativeSummary", "打击感很扎实",
                                "keywords", "打击感,手感",
                                "latestAt", LocalDateTime.of(2026, 6, 8, 22, 0)
                        ),
                        Map.of(
                                "groupId", "G2",
                                "module", "画面表现",
                                "count", 1,
                                "representativeSummary", "画面层次很好",
                                "keywords", "画面,表现",
                                "latestAt", LocalDateTime.of(2026, 6, 8, 20, 0)
                        )
                ));

        DashboardVO overview = deps.service.getOverview(1L, start, end);
        Map<String, Object> praiseBoard = overview.getPraiseBoard();
        List<Map<String, Object>> highlights = rows(praiseBoard.get("highlights"));

        assertThat(highlights).hasSize(2);
        assertThat(titles(highlights)).containsExactly("打击感很扎实", "画面层次很好");
        assertThat(highlights.get(0).get("module")).isEqualTo("战斗系统");
        assertThat(highlights.get(0).get("count")).isEqualTo(2);
        assertThat(String.valueOf(highlights.get(0).get("keywords"))).contains("打击感");
        assertThat(highlights.get(0).get("latestAt")).isEqualTo(LocalDateTime.of(2026, 6, 8, 22, 0));
    }

    private void stubOverviewDefaults(Dependencies deps, LocalDateTime start, LocalDateTime end) {
        when(deps.issueRepo.searchIssues(eq(1L), eq(null), eq(IssueStatusEnum.OPEN), eq(null), eq(null), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(deps.issueRepo.findTopIssuesByProductId(eq(1L), eq(List.of(IssueStatusEnum.MERGED, IssueStatusEnum.CLOSED)), any(PageRequest.class)))
                .thenReturn(List.of());
        when(deps.issueRepo.findRecentNewIssues(
                eq(1L),
                eq(FeedbackCategoryEnum.SUGGESTION),
                eq(List.of(IssueStatusEnum.MERGED, IssueStatusEnum.CLOSED)),
                eq(start),
                eq(end)))
                .thenReturn(List.of());
        when(deps.claimRepo.countTopIssueIdsByCategory(
                eq(1L),
                eq(FeedbackCategoryEnum.SUGGESTION),
                eq(List.of(IssueStatusEnum.MERGED, IssueStatusEnum.CLOSED)),
                eq(start),
                eq(end)))
                .thenReturn(List.of());
        when(deps.analyzedRepo.countGroupByCategory(1L, start, end)).thenReturn(List.of());
        when(deps.analyzedRepo.countGroupByModule(1L, start, end)).thenReturn(List.of());
        when(deps.rawRepo.countGroupByChannel(1L, start, end)).thenReturn(List.of());
        when(deps.claimRepo.countModulesByCategory(1L, FeedbackCategoryEnum.BUG, start, end)).thenReturn(List.of());
        when(deps.claimRepo.countModulesByCategory(1L, FeedbackCategoryEnum.SUGGESTION, start, end)).thenReturn(List.of());
        when(deps.claimRepo.countModulesByCategoryAndStatus(1L, FeedbackCategoryEnum.PRAISE, FeedbackClaimStatusEnum.RECORDED, start, end))
                .thenReturn(List.of());
        when(deps.praiseAggregationService.buildGroups(1L, start, end, 10)).thenReturn(List.of());
    }

    private List<Map<String, Object>> rows(Object value) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) value;
        return rows;
    }

    private List<String> titles(Object value) {
        return rows(value).stream()
                .map(row -> String.valueOf(row.getOrDefault("title", row.get("representativeSummary"))))
                .toList();
    }

    private List<String> severities(Object value) {
        return rows(value).stream()
                .map(row -> String.valueOf(row.get("severity")))
                .toList();
    }

    private Object[] row(String issueId, long windowCount) {
        return new Object[]{issueId, windowCount};
    }

    private FeedbackIssue issue(String id,
                                String title,
                                String module,
                                String severity,
                                String priority,
                                int reportCount,
                                LocalDateTime firstReportAt,
                                LocalDateTime latestReportAt) {
        return FeedbackIssue.builder()
                .id(id)
                .productId(1L)
                .title(title)
                .category(FeedbackCategoryEnum.BUG)
                .module(module)
                .severity(severity)
                .priority(priority)
                .status(IssueStatusEnum.OPEN)
                .reportCount(reportCount)
                .firstReportAt(firstReportAt)
                .latestReportAt(latestReportAt)
                .build();
    }

    private Dependencies dependencies() {
        FeedbackRawRepository rawRepo = mock(FeedbackRawRepository.class);
        FeedbackAnalyzedRepository analyzedRepo = mock(FeedbackAnalyzedRepository.class);
        FeedbackIssueRepository issueRepo = mock(FeedbackIssueRepository.class);
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        PraiseAggregationService praiseAggregationService = mock(PraiseAggregationService.class);
        DashboardServiceImpl service = new DashboardServiceImpl(rawRepo, analyzedRepo, issueRepo, claimRepo, praiseAggregationService);
        return new Dependencies(service, rawRepo, analyzedRepo, issueRepo, claimRepo, praiseAggregationService);
    }

    private record Dependencies(DashboardServiceImpl service,
                                FeedbackRawRepository rawRepo,
                                FeedbackAnalyzedRepository analyzedRepo,
                                FeedbackIssueRepository issueRepo,
                                FeedbackClaimRepository claimRepo,
                                PraiseAggregationService praiseAggregationService) {
    }
}
