package com.feedback.analyzer.handler;

import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import com.feedback.analyzer.repository.ProductRepository;
import com.feedback.analyzer.service.FeedbackService;
import com.feedback.analyzer.service.PublicReviewSourceService;
import com.feedback.analyzer.service.WeeklyReportService;
import com.feedback.analyzer.service.impl.PublicReviewRunCoordinator;
import com.feedback.analyzer.service.impl.ZenTaoWebhookEventService;
import com.feedback.analyzer.model.vo.WeeklyReportVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTaskHandler {

    private final FeedbackRawRepository rawRepo;
    private final ProductRepository productRepo;
    private final FeedbackService feedbackService;
    private final WeeklyReportService weeklyReportService;
    private final PublicReviewSourceService publicReviewSourceService;
    private final PublicReviewRunCoordinator publicReviewRunCoordinator;
    private final ZenTaoWebhookEventService zenTaoWebhookEventService;

    @Value("${feedback.processing.raw-stuck-hours:2}")
    private long rawStuckHours;

    @Value("${feedback.processing.analyzing-timeout-minutes:60}")
    private long analyzingTimeoutMinutes;

    @Scheduled(cron = "0 0 * * * *")
    public void collectPublicReviews() {
        publicReviewSourceService.collectScheduled();
    }

    @Scheduled(fixedDelay = 600_000)
    public void rescueStuckFeedbacks() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(rawStuckHours);
        List<FeedbackRaw> stuckRaw = rawRepo.findStuckRecords(FeedbackStatusEnum.RAW, threshold);
        log.info("Found {} stuck raw feedbacks", stuckRaw.size());
        for (FeedbackRaw raw : stuckRaw) {
            try {
                feedbackService.processRaw(raw.getId());
            } catch (Exception e) {
                log.warn("Failed to rescue raw feedback {}", raw.getId(), e);
            }
        }

        LocalDateTime analyzingThreshold = LocalDateTime.now().minusMinutes(analyzingTimeoutMinutes);
        List<FeedbackRaw> stuckAnalyzing = rawRepo.findByStatusAndProcessingStartedAtBefore(
                FeedbackStatusEnum.ANALYZING, analyzingThreshold);
        log.info("Found {} timed-out analyzing feedbacks", stuckAnalyzing.size());
        for (FeedbackRaw raw : stuckAnalyzing) {
            try {
                feedbackService.retryTimedOutAnalyzing(raw.getId());
            } catch (Exception e) {
                log.warn("Failed to retry analyzing feedback {}", raw.getId(), e);
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void reconcilePublicReviewRuns() {
        try {
            publicReviewRunCoordinator.reconcileProcessingRuns();
        } catch (Exception e) {
            log.warn("Public review processing run reconciliation failed", e);
        }
    }

    @Scheduled(fixedDelay = 10_000)
    public void processZenTaoWebhookEvents() {
        try {
            zenTaoWebhookEventService.processDueEvents();
        } catch (Exception e) {
            log.warn("ZenTao webhook event processing failed", e);
        }
    }

    @Scheduled(cron = "0 0 9 * * MON")
    public void generateWeeklyReport() {
        try {
            LocalDate previousWeekStart = LocalDate.now().minusWeeks(1).with(DayOfWeek.MONDAY);
            productRepo.findAll().forEach(product -> {
                try {
                    if (!Boolean.TRUE.equals(product.getEnabled())) {
                        log.info("Skip weekly report for disabled product: {}", product.getId());
                        return;
                    }
                    WeeklyReportVO report = weeklyReportService.generate(product.getId(), previousWeekStart.toString());
                    if (Boolean.TRUE.equals(product.getFeishuEnabled()) && product.isFeishuConfigured()) {
                        weeklyReportService.send(report.getId());
                    } else {
                        log.info("Weekly report generated without Feishu send: productId={}, reportId={}",
                                product.getId(), report.getId());
                    }
                } catch (Exception e) {
                    log.error("Failed to generate weekly report for product: {}", product.getId(), e);
                }
            });
            log.info("Weekly reports generated successfully");
        } catch (Exception e) {
            log.error("Failed to generate weekly reports", e);
        }
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void cleanExpiredData() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(90);
        List<FeedbackRaw> expired = rawRepo.findStuckRecords(FeedbackStatusEnum.LOW_QUALITY, threshold);
        if (!expired.isEmpty()) {
            rawRepo.deleteAll(expired);
            log.info("Cleaned {} expired low-quality feedbacks", expired.size());
        }
    }
}
