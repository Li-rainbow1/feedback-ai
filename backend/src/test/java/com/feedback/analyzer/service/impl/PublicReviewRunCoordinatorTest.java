package com.feedback.analyzer.service.impl;

import com.feedback.analyzer.entity.FeedbackAnalyzed;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.entity.IssueTimeline;
import com.feedback.analyzer.entity.PublicReviewCollectRun;
import com.feedback.analyzer.model.dto.AiIssueDecision;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.repository.FeedbackAnalyzedRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import com.feedback.analyzer.repository.IssueTimelineRepository;
import com.feedback.analyzer.repository.PublicReviewCollectRunRepository;
import com.feedback.analyzer.service.ai.IssueProgressDecisionService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicReviewRunCoordinatorTest {

    @Test
    void reconciliationFinishesProcessingRunWhenAllRawAreTerminal() throws Exception {
        Dependencies deps = dependencies();
        PublicReviewCollectRun run = processingRun(10L);
        FeedbackRaw raw = raw("RAW-1", FeedbackStatusEnum.ANALYZED, run.getId());
        FeedbackAnalyzed analyzed = FeedbackAnalyzed.builder()
                .id("FA-1")
                .rawId(raw.getId())
                .issueId("ISSUE-1")
                .build();
        FeedbackIssue issue = FeedbackIssue.builder()
                .id("ISSUE-1")
                .category(FeedbackCategoryEnum.BUG)
                .status(IssueStatusEnum.OPEN)
                .build();
        when(deps.runRepo.findByStatusOrderByStartedAtAsc(eq("PROCESSING"), any(Pageable.class)))
                .thenReturn(List.of(run));
        when(deps.runRepo.findById(run.getId())).thenReturn(Optional.of(run));
        when(deps.rawRepo.findByCollectionRunId(run.getId())).thenReturn(List.of(raw));
        when(deps.analyzedRepo.findByRawIdIn(List.of(raw.getId()))).thenReturn(List.of(analyzed));
        when(deps.issueRepo.findById(issue.getId())).thenReturn(Optional.of(issue));
        when(deps.timelineRepo.findByIssueIdOrderByCreatedAtDesc(issue.getId())).thenReturn(List.of());
        when(deps.issueProgressDecisionService.progress(issue)).thenAnswer(invocation -> {
            issue.setRelatedIssue("ZT-BUG-24");
            return AiIssueDecision.builder()
                    .zentaoAction("CREATE_BUG")
                    .zentaoIssueKey("ZT-BUG-24")
                    .reason("创建成功")
                    .build();
        });

        deps.coordinator.reconcileProcessingRuns();

        assertThat(run.getStatus()).isEqualTo("SUCCESS");
        assertThat(run.getProcessedCount()).isEqualTo(1);
        assertThat(run.getAffectedIssueCount()).isEqualTo(1);
        assertThat(run.getFinishedAt()).isNotNull();
        verify(deps.issueProgressDecisionService).progress(issue);
        verify(deps.timelineRepo).save(any(IssueTimeline.class));
        verify(deps.runRepo).save(run);
    }

    @Test
    void processingRunStaysProcessingWhenZenTaoProgressIsNotCompleted() throws Exception {
        Dependencies deps = dependencies();
        PublicReviewCollectRun run = processingRun(12L);
        FeedbackRaw raw = raw("RAW-1", FeedbackStatusEnum.ANALYZED, run.getId());
        FeedbackAnalyzed analyzed = FeedbackAnalyzed.builder()
                .id("FA-1")
                .rawId(raw.getId())
                .issueId("ISSUE-1")
                .build();
        FeedbackIssue issue = FeedbackIssue.builder()
                .id("ISSUE-1")
                .category(FeedbackCategoryEnum.BUG)
                .status(IssueStatusEnum.OPEN)
                .build();
        when(deps.runRepo.findByStatusOrderByStartedAtAsc(eq("PROCESSING"), any(Pageable.class)))
                .thenReturn(List.of(run));
        when(deps.runRepo.findById(run.getId())).thenReturn(Optional.of(run));
        when(deps.rawRepo.findByCollectionRunId(run.getId())).thenReturn(List.of(raw));
        when(deps.analyzedRepo.findByRawIdIn(List.of(raw.getId()))).thenReturn(List.of(analyzed));
        when(deps.issueRepo.findById(issue.getId())).thenReturn(Optional.of(issue));
        when(deps.timelineRepo.findByIssueIdOrderByCreatedAtDesc(issue.getId())).thenReturn(List.of());
        when(deps.issueProgressDecisionService.progress(issue)).thenReturn(AiIssueDecision.builder()
                .zentaoAction("NOOP")
                .reason("禅道不可用")
                .build());

        deps.coordinator.reconcileProcessingRuns();

        assertThat(run.getStatus()).isEqualTo("PROCESSING");
        assertThat(run.getProcessedCount()).isEqualTo(1);
        assertThat(run.getAffectedIssueCount()).isEqualTo(1);
        assertThat(run.getFinishedAt()).isNull();
        assertThat(run.getErrorMessage()).contains("禅道推进未完成");
        verify(deps.issueProgressDecisionService).progress(issue);
        verify(deps.timelineRepo).save(any(IssueTimeline.class));
        verify(deps.runRepo).save(run);
    }

    @Test
    void processingRunWithUnfinishedRawDoesNotProgressIssue() throws Exception {
        Dependencies deps = dependencies();
        PublicReviewCollectRun run = processingRun(11L);
        FeedbackRaw raw = raw("RAW-1", FeedbackStatusEnum.ANALYZING, run.getId());
        when(deps.runRepo.findByStatusOrderByStartedAtAsc(eq("PROCESSING"), any(Pageable.class)))
                .thenReturn(List.of(run));
        when(deps.runRepo.findById(run.getId())).thenReturn(Optional.of(run));
        when(deps.rawRepo.findByCollectionRunId(run.getId())).thenReturn(List.of(raw));

        deps.coordinator.reconcileProcessingRuns();

        assertThat(run.getStatus()).isEqualTo("PROCESSING");
        verify(deps.issueProgressDecisionService, never()).progress(any());
        verify(deps.runRepo, never()).save(run);
    }

    private Dependencies dependencies() throws InterruptedException {
        PublicReviewCollectRunRepository runRepo = mock(PublicReviewCollectRunRepository.class);
        FeedbackRawRepository rawRepo = mock(FeedbackRawRepository.class);
        FeedbackAnalyzedRepository analyzedRepo = mock(FeedbackAnalyzedRepository.class);
        FeedbackIssueRepository issueRepo = mock(FeedbackIssueRepository.class);
        IssueTimelineRepository timelineRepo = mock(IssueTimelineRepository.class);
        IssueProgressDecisionService issueProgressDecisionService = mock(IssueProgressDecisionService.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        PublicReviewRunCoordinator coordinator = new PublicReviewRunCoordinator(
                runRepo, rawRepo, analyzedRepo, issueRepo, timelineRepo, issueProgressDecisionService, redisson);
        return new Dependencies(coordinator, runRepo, rawRepo, analyzedRepo, issueRepo, timelineRepo, issueProgressDecisionService);
    }

    private PublicReviewCollectRun processingRun(Long runId) {
        return PublicReviewCollectRun.builder()
                .id(runId)
                .sourceId(1L)
                .productId(1L)
                .runType("MANUAL")
                .status("PROCESSING")
                .startedAt(LocalDateTime.now().minusMinutes(5))
                .build();
    }

    private FeedbackRaw raw(String rawId, FeedbackStatusEnum status, Long runId) {
        return FeedbackRaw.builder()
                .id(rawId)
                .productId(1L)
                .channel("steam")
                .rawContent("测试评论")
                .feedbackTime(LocalDateTime.now())
                .collectionRunId(runId)
                .status(status)
                .build();
    }

    private record Dependencies(PublicReviewRunCoordinator coordinator,
                                PublicReviewCollectRunRepository runRepo,
                                FeedbackRawRepository rawRepo,
                                FeedbackAnalyzedRepository analyzedRepo,
                                FeedbackIssueRepository issueRepo,
                                IssueTimelineRepository timelineRepo,
                                IssueProgressDecisionService issueProgressDecisionService) {
    }
}
