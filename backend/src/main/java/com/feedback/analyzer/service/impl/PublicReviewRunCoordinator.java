package com.feedback.analyzer.service.impl;

import com.feedback.analyzer.entity.FeedbackAnalyzed;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.entity.IssueTimeline;
import com.feedback.analyzer.entity.PublicReviewCollectRun;
import com.feedback.analyzer.model.dto.AiIssueDecision;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import com.feedback.analyzer.repository.FeedbackAnalyzedRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import com.feedback.analyzer.repository.IssueTimelineRepository;
import com.feedback.analyzer.repository.PublicReviewCollectRunRepository;
import com.feedback.analyzer.service.ai.IssueProgressDecisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublicReviewRunCoordinator {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_SUCCESS = "SUCCESS";

    private final PublicReviewCollectRunRepository runRepo;
    private final FeedbackRawRepository rawRepo;
    private final FeedbackAnalyzedRepository analyzedRepo;
    private final FeedbackIssueRepository issueRepo;
    private final IssueTimelineRepository timelineRepo;
    private final IssueProgressDecisionService issueProgressDecisionService;
    private final RedissonClient redisson;

    public void onFeedbackHandled(Long runId) {
        if (runId == null) {
            return;
        }
        finishIfReady(runId);
    }

    public void reconcileProcessingRuns() {
        List<PublicReviewCollectRun> runs = runRepo.findByStatusOrderByStartedAtAsc(
                STATUS_PROCESSING, PageRequest.of(0, 50));
        if (runs.isEmpty()) {
            return;
        }
        log.info("Found {} public review processing runs to reconcile", runs.size());
        for (PublicReviewCollectRun run : runs) {
            finishIfReady(run.getId());
        }
    }

    private void finishIfReady(Long runId) {
        RLock lock = redisson.getLock("public-review:finish:" + runId);
        boolean locked = false;
        try {
            locked = lock.tryLock(2, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            PublicReviewCollectRun run = runRepo.findById(runId).orElse(null);
            if (run == null || !STATUS_PROCESSING.equals(run.getStatus())) {
                return;
            }

            List<FeedbackRaw> rawItems = rawRepo.findByCollectionRunId(runId);
            if (rawItems.stream().anyMatch(this::isUnfinished)) {
                return;
            }

            List<String> rawIds = rawItems.stream().map(FeedbackRaw::getId).toList();
            List<String> issueIds = rawIds.isEmpty() ? List.of() : analyzedRepo.findByRawIdIn(rawIds).stream()
                    .map(FeedbackAnalyzed::getIssueId)
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .toList();

            int affectedBugCount = 0;
            boolean allBugProgressed = true;
            for (String issueId : issueIds) {
                FeedbackIssue issue = issueRepo.findById(issueId).orElse(null);
                if (isBug(issue)) {
                    affectedBugCount++;
                    if (isIssueProgressedForRun(issue.getId(), runId)) {
                        continue;
                    }
                    AiIssueDecision decision = issueProgressDecisionService.progress(issue);
                    FeedbackIssue refreshed = issueRepo.findById(issue.getId()).orElse(issue);
                    if (hasRelatedZenTaoIssue(refreshed)) {
                        markIssueProgressedForRun(issue.getId(), runId, "public_review_batch_progressed",
                                "公开评论批次已统一推进禅道，" + progressMarker(runId) + "，禅道 Bug：" + refreshed.getRelatedIssue());
                    } else {
                        allBugProgressed = false;
                        markIssueProgressedForRun(issue.getId(), runId, "public_review_batch_progress_failed",
                                "公开评论批次禅道推进未完成，" + progressMarker(runId)
                                        + "，动作：" + value(decision != null ? decision.getZentaoAction() : null)
                                        + "，原因：" + value(decision != null ? decision.getReason() : null));
                    }
                }
            }

            run.setProcessedCount(rawItems.size());
            run.setAffectedIssueCount(affectedBugCount);
            if (!allBugProgressed) {
                run.setErrorMessage("公开评论批次禅道推进未完成，等待下次补偿");
                runRepo.save(run);
                return;
            }
            run.setStatus(STATUS_SUCCESS);
            run.setFinishedAt(LocalDateTime.now());
            run.setErrorMessage(null);
            runRepo.save(run);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("公开评论批次收尾被中断，runId={}", runId);
        } catch (Exception e) {
            log.warn("公开评论批次收尾失败，runId={}: {}", runId, e.getMessage(), e);
            runRepo.findById(runId).ifPresent(run -> {
                run.setErrorMessage(e.getMessage());
                runRepo.save(run);
            });
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private boolean isUnfinished(FeedbackRaw raw) {
        return raw.getStatus() == FeedbackStatusEnum.RAW || raw.getStatus() == FeedbackStatusEnum.ANALYZING;
    }

    private boolean isBug(FeedbackIssue issue) {
        return issue != null && issue.getCategory() == FeedbackCategoryEnum.BUG;
    }

    private boolean hasRelatedZenTaoIssue(FeedbackIssue issue) {
        return issue != null
                && issue.getRelatedIssue() != null
                && !issue.getRelatedIssue().isBlank()
                && issue.getRelatedIssue().startsWith("ZT-BUG-");
    }

    private boolean isIssueProgressedForRun(String issueId, Long runId) {
        if (issueId == null || runId == null) {
            return false;
        }
        String marker = progressMarker(runId);
        return timelineRepo.findByIssueIdOrderByCreatedAtDesc(issueId).stream()
                .anyMatch(item -> "public_review_batch_progressed".equals(item.getEventType())
                        && item.getDetail() != null
                        && item.getDetail().contains(marker));
    }

    private void markIssueProgressedForRun(String issueId, Long runId, String eventType, String detail) {
        if (hasTimelineForRun(issueId, runId, eventType)) {
            return;
        }
        timelineRepo.save(IssueTimeline.builder()
                .issueId(issueId)
                .eventType(eventType)
                .detail(detail)
                .build());
    }

    private boolean hasTimelineForRun(String issueId, Long runId, String eventType) {
        if (issueId == null || runId == null || eventType == null || eventType.isBlank()) {
            return false;
        }
        String marker = progressMarker(runId);
        return timelineRepo.findByIssueIdOrderByCreatedAtDesc(issueId).stream()
                .anyMatch(item -> eventType.equals(item.getEventType())
                        && item.getDetail() != null
                        && item.getDetail().contains(marker));
    }

    private String progressMarker(Long runId) {
        return "runId=" + runId;
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
