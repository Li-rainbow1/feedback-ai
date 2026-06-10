package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.entity.Product;
import com.feedback.analyzer.entity.WeeklyReport;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.model.vo.DashboardVO;
import com.feedback.analyzer.repository.FeedbackClaimRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import com.feedback.analyzer.repository.ProductRepository;
import com.feedback.analyzer.repository.WeeklyReportRepository;
import com.feedback.analyzer.service.DashboardService;
import com.feedback.analyzer.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyReportServiceImplTest {

    @Test
    void leavesReportUnsentWhenFeishuDeliveryFails() {
        WeeklyReportRepository reportRepo = mock(WeeklyReportRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        WeeklyReport report = report(1L);
        when(reportRepo.findById(1L)).thenReturn(Optional.of(report));
        when(productRepo.findById(1L)).thenReturn(Optional.of(feishuReadyProduct()));
        when(notificationService.notifyReportReady(1L, 1L, "2026-05-18 ~ 2026-05-24", "本周报告"))
                .thenReturn(false);

        WeeklyReportServiceImpl service = service(reportRepo, productRepo, notificationService);

        assertThatThrownBy(() -> service.send(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("飞书周报发送失败");
        assertThat(report.getIsSent()).isFalse();
        verify(reportRepo, never()).saveAndFlush(any());
    }

    @Test
    void marksReportSentAfterFeishuDeliverySucceeds() {
        WeeklyReportRepository reportRepo = mock(WeeklyReportRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        WeeklyReport report = report(1L);
        when(reportRepo.findById(1L)).thenReturn(Optional.of(report));
        when(productRepo.findById(1L)).thenReturn(Optional.of(feishuReadyProduct()));
        when(notificationService.notifyReportReady(1L, 1L, "2026-05-18 ~ 2026-05-24", "本周报告"))
                .thenReturn(true);

        WeeklyReportServiceImpl service = service(reportRepo, productRepo, notificationService);
        service.send(1L);

        assertThat(report.getIsSent()).isTrue();
        assertThat(report.getSentAt()).isNotNull();
        verify(reportRepo).saveAndFlush(report);
    }

    @Test
    void allowsResendingReportThatWasAlreadySent() {
        WeeklyReportRepository reportRepo = mock(WeeklyReportRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        WeeklyReport report = report(1L);
        report.setIsSent(true);
        when(reportRepo.findById(1L)).thenReturn(Optional.of(report));
        when(productRepo.findById(1L)).thenReturn(Optional.of(feishuReadyProduct()));
        when(notificationService.notifyReportReady(1L, 1L, "2026-05-18 ~ 2026-05-24", "本周报告"))
                .thenReturn(true);

        WeeklyReportServiceImpl service = service(reportRepo, productRepo, notificationService);

        service.send(1L);

        assertThat(report.getIsSent()).isTrue();
        assertThat(report.getSentAt()).isNotNull();
        verify(notificationService).notifyReportReady(1L, 1L, "2026-05-18 ~ 2026-05-24", "本周报告");
        verify(reportRepo).saveAndFlush(report);
    }

    @Test
    void rejectsSendWhenProductFeishuWebhookMissing() {
        WeeklyReportRepository reportRepo = mock(WeeklyReportRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        WeeklyReport report = report(1L);
        when(reportRepo.findById(1L)).thenReturn(Optional.of(report));
        when(productRepo.findById(1L)).thenReturn(Optional.of(Product.builder()
                .id(1L)
                .name("黑神话悟空")
                .feishuEnabled(true)
                .feishuWebhookUrl("")
                .build()));

        WeeklyReportServiceImpl service = service(reportRepo, productRepo, notificationService);

        assertThatThrownBy(() -> service.send(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("启用飞书通知并配置机器人地址");
        assertThat(report.getIsSent()).isFalse();
        verify(notificationService, never()).notifyReportReady(any(), any(), any(), any());
        verify(reportRepo, never()).saveAndFlush(any());
    }

    @Test
    void regeneratesSameWeekReportWithDeterministicMarkdownAndBugTop5() {
        WeeklyReportRepository reportRepo = mock(WeeklyReportRepository.class);
        FeedbackRawRepository rawRepo = mock(FeedbackRawRepository.class);
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        FeedbackIssueRepository issueRepo = mock(FeedbackIssueRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        DashboardService dashboardService = mock(DashboardService.class);
        WeeklyReport existing = report(2L);
        LocalDateTime sentAt = LocalDateTime.of(2026, 5, 25, 9, 30);
        existing.setIsSent(true);
        existing.setSentAt(sentAt);

        when(productRepo.findById(1L)).thenReturn(Optional.of(product(1L, "黑神话悟空")));
        when(reportRepo.findByProductIdAndWeekStart(1L, LocalDate.of(2026, 5, 18)))
                .thenReturn(Optional.of(existing));
        when(rawRepo.countByProductIdAndCreatedAtBetween(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(10L);
        when(rawRepo.countByProductIdAndStatusAndCreatedAtBetween(eq(1L), eq(FeedbackStatusEnum.ANALYZED), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(6L);
        when(rawRepo.countByProductIdAndStatusAndCreatedAtBetween(eq(1L), eq(FeedbackStatusEnum.LOW_QUALITY), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(3L);
        when(rawRepo.countByProductIdAndStatusAndCreatedAtBetween(eq(1L), eq(FeedbackStatusEnum.SKIPPED), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1L);
        when(claimRepo.countByProductIdAndCreatedAtBetween(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(12L);
        when(issueRepo.countByProductIdAndCategoryAndLatestReportAtBetween(eq(1L), any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(3L);
        when(issueRepo.countByProductIdAndCategoryAndFirstReportAtBetween(eq(1L), any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(2L);
        when(issueRepo.countByProductIdAndCategoryAndSeverityAndLatestReportAtBetween(eq(1L), any(), eq("CRITICAL"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(0L);
        when(issueRepo.countByProductIdAndCategoryAndSeverityAndLatestReportAtBetween(eq(1L), any(), eq("HIGH"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1L);
        when(issueRepo.countByProductIdAndCategoryAndSeverityAndLatestReportAtBetween(eq(1L), any(), eq("MEDIUM"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1L);
        when(issueRepo.countByProductIdAndCategoryAndSeverityAndLatestReportAtBetween(eq(1L), any(), eq("LOW"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1L);
        when(dashboardService.getOverview(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(DashboardVO.builder()
                        .bugBoard(Map.of("claimCount", 7L))
                        .suggestionBoard(Map.of(
                                "claimCount", 3L,
                                "topIssues", List.of(Map.of(
                                        "title", "增加自动存档提示",
                                        "windowCount", 2L,
                                        "reportCount", 4L,
                                        "statusLabel", "待评估"
                                ))
                        ))
                        .praiseBoard(Map.of(
                                "recordedCount", 1L,
                                "ignoredCount", 2L,
                                "highlights", List.of(Map.of(
                                        "module", "战斗手感",
                                        "representativeSummary", "战斗反馈很扎实",
                                        "count", 6L,
                                        "latestAt", "2026-05-20T08:30:00"
                                ))
                        ))
                        .build());
        when(issueRepo.findUrgentIssues(eq(1L), eq(FeedbackCategoryEnum.BUG), any(), any(LocalDateTime.class), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(
                        issue("ISSUE-HIGH", "过场 CG 卡死", "HIGH", "P2", 5, LocalDateTime.of(2026, 5, 20, 8, 0)),
                        issue("ISSUE-MEDIUM", "空气墙导致角色神游", "MEDIUM", "P3", 4, LocalDateTime.of(2026, 5, 20, 7, 0)),
                        issue("ISSUE-LOW", "文案错别字", "LOW", "P4", 2, LocalDateTime.of(2026, 5, 20, 6, 0))
                ));
        when(claimRepo.countDistinctRawIdByIssueIdAndCreatedAtBetween(eq("ISSUE-HIGH"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(2L);
        when(claimRepo.countDistinctRawIdByIssueIdAndCreatedAtBetween(eq("ISSUE-MEDIUM"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1L);
        when(claimRepo.countDistinctRawIdByIssueIdAndCreatedAtBetween(eq("ISSUE-LOW"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1L);
        when(reportRepo.save(existing)).thenReturn(existing);

        WeeklyReportServiceImpl service = new WeeklyReportServiceImpl(
                reportRepo,
                rawRepo,
                claimRepo,
                issueRepo,
                productRepo,
                mock(NotificationService.class),
                dashboardService,
                new ObjectMapper(),
                mock(RedissonClient.class));

        service.generate(1L, "2026-05-18");

        assertThat(existing.getIsSent()).isFalse();
        assertThat(existing.getSentAt()).isNull();
        assertThat(existing.getContent()).contains("# 黑神话悟空 2026-05-18 ~ 2026-05-24 周报");
        assertThat(existing.getContent()).contains("接入原始反馈：10 条");
        assertThat(existing.getContent()).contains("低质量反馈：3 条");
        assertThat(existing.getContent()).doesNotContain("去重跳过反馈");
        assertThat(existing.getContent()).contains("Bug 片段：7 个；建议片段：3 个；有效好评：1 条");
        assertThat(existing.getContent()).contains("本周活跃 Bug 问题：3 个，其中 CRITICAL 0 个、HIGH 1 个、MEDIUM 1 个、LOW 1 个");
        assertThat(existing.getContent()).contains("## 二、Bug 预警 Top5");
        assertThat(existing.getContent()).contains("HIGH / P2 / 待处理：过场 CG 卡死（本周新增反馈：2，累计反馈：5）");
        assertThat(existing.getContent()).contains("MEDIUM / P3 / 待处理：空气墙导致角色神游（本周新增反馈：1，累计反馈：4）");
        assertThat(existing.getContent()).contains("LOW / P4 / 待处理：文案错别字（本周新增反馈：1，累计反馈：2）");
        assertThat(existing.getContent()).contains("战斗手感：战斗反馈很扎实（累计好评：6）");
        assertThat(existing.getContent()).doesNotContain("最近 24 小时新增");
        assertThat(existing.getContent()).doesNotContain("2026-05-20T08:30:00");
        assertThat(existing.getRawData()).contains("\"productName\":\"黑神话悟空\"");
        assertThat(existing.getRawData()).contains("\"rawFeedbackCount\":10");
        assertThat(existing.getRawData()).contains("\"lowQualityFeedbackCount\":3");
        assertThat(existing.getRawData()).contains("\"skippedFeedbackCount\":1");
        verify(reportRepo).save(existing);
    }

    private WeeklyReportServiceImpl service(WeeklyReportRepository reportRepo,
                                            ProductRepository productRepo,
                                            NotificationService notificationService) {
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        try {
            when(redisson.getLock("weekly-report:send:1")).thenReturn(lock);
            when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
            when(lock.isHeldByCurrentThread()).thenReturn(true);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return new WeeklyReportServiceImpl(
                reportRepo,
                mock(FeedbackRawRepository.class),
                mock(FeedbackClaimRepository.class),
                mock(FeedbackIssueRepository.class),
                productRepo,
                notificationService,
                mock(DashboardService.class),
                new ObjectMapper(),
                redisson);
    }

    private WeeklyReport report(Long id) {
        return WeeklyReport.builder()
                .id(id)
                .productId(1L)
                .weekStart(LocalDate.of(2026, 5, 18))
                .weekEnd(LocalDate.of(2026, 5, 24))
                .content("本周报告")
                .isSent(false)
                .build();
    }

    private Product product(Long id, String name) {
        return Product.builder()
                .id(id)
                .name(name)
                .enabled(true)
                .webhookToken("token")
                .build();
    }

    private Product feishuReadyProduct() {
        return Product.builder()
                .id(1L)
                .name("黑神话悟空")
                .feishuEnabled(true)
                .feishuWebhookUrl("https://example.invalid/feishu-webhook-test")
                .build();
    }

    private FeedbackIssue issue(String id,
                                String title,
                                String severity,
                                String priority,
                                int reportCount,
                                LocalDateTime latestReportAt) {
        return FeedbackIssue.builder()
                .id(id)
                .title(title)
                .category(FeedbackCategoryEnum.BUG)
                .severity(severity)
                .priority(priority)
                .status(IssueStatusEnum.OPEN)
                .reportCount(reportCount)
                .latestReportAt(latestReportAt)
                .build();
    }
}
