package com.feedback.analyzer.service.impl;

import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.entity.IssueTimeline;
import com.feedback.analyzer.entity.ZenTaoWebhookEvent;
import com.feedback.analyzer.model.dto.ZenTaoBugSnapshot;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.IssueTimelineRepository;
import com.feedback.analyzer.repository.ZenTaoWebhookEventRepository;
import com.feedback.analyzer.service.ZenTaoService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZenTaoWebhookEventServiceTest {

    @Test
    void processesEventAndSynchronizesIssueSnapshot() {
        Dependencies deps = dependencies();
        ZenTaoWebhookEvent event = event("ZT-BUG-24");
        FeedbackIssue issue = FeedbackIssue.builder()
                .id("ISSUE-24")
                .relatedIssue("ZT-BUG-24")
                .category(FeedbackCategoryEnum.BUG)
                .status(IssueStatusEnum.OPEN)
                .severity("MEDIUM")
                .priority("P3")
                .build();
        when(deps.eventRepo.findByStatusInAndNextRetryAtLessThanEqualOrderByReceivedAtAsc(
                anyCollection(), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(deps.issueRepo.findByRelatedIssue("ZT-BUG-24")).thenReturn(Optional.of(issue));
        when(deps.zenTaoService.fetchBugSnapshot("ZT-BUG-24"))
                .thenReturn(new ZenTaoBugSnapshot("ZT-BUG-24", "resolved", true, "HIGH", "P1"));

        deps.service.processDueEvents();

        assertThat(issue.getStatus()).isEqualTo(IssueStatusEnum.RESOLVED);
        assertThat(issue.getSeverity()).isEqualTo("HIGH");
        assertThat(issue.getPriority()).isEqualTo("P1");
        assertThat(issue.getConfirmed()).isTrue();
        assertThat(issue.getTriageSource()).isEqualTo("ZENTAO_WEBHOOK");
        assertThat(event.getStatus()).isEqualTo("SUCCESS");
        assertThat(event.getProcessedAt()).isNotNull();
        verify(deps.issueRepo).save(issue);
        ArgumentCaptor<IssueTimeline> timeline = ArgumentCaptor.forClass(IssueTimeline.class);
        verify(deps.timelineRepo, org.mockito.Mockito.times(3)).save(timeline.capture());
        assertThat(timeline.getAllValues())
                .extracting(IssueTimeline::getEventType)
                .contains("zentao_status_synced", "zentao_confirmed_synced", "zentao_triage_synced");
    }

    @Test
    void schedulesRetryWhenZenTaoSnapshotFails() {
        Dependencies deps = dependencies();
        ZenTaoWebhookEvent event = event("ZT-BUG-24");
        FeedbackIssue issue = FeedbackIssue.builder()
                .id("ISSUE-24")
                .relatedIssue("ZT-BUG-24")
                .status(IssueStatusEnum.OPEN)
                .severity("MEDIUM")
                .priority("P3")
                .build();
        when(deps.eventRepo.findByStatusInAndNextRetryAtLessThanEqualOrderByReceivedAtAsc(
                anyCollection(), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(deps.issueRepo.findByRelatedIssue("ZT-BUG-24")).thenReturn(Optional.of(issue));
        when(deps.zenTaoService.fetchBugSnapshot("ZT-BUG-24"))
                .thenThrow(new IllegalStateException("连接失败"));

        deps.service.processDueEvents();

        assertThat(event.getStatus()).isEqualTo("RETRY");
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getNextRetryAt()).isAfter(LocalDateTime.now().minusSeconds(1));
        assertThat(issue.getSeverity()).isEqualTo("MEDIUM");
        verify(deps.issueRepo, never()).save(any());
        verify(deps.timelineRepo, never()).save(any());
    }

    @Test
    void marksEventSuccessWhenLocalIssueIsMissing() {
        Dependencies deps = dependencies();
        ZenTaoWebhookEvent event = event("ZT-BUG-99");
        when(deps.eventRepo.findByStatusInAndNextRetryAtLessThanEqualOrderByReceivedAtAsc(
                anyCollection(), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(deps.issueRepo.findByRelatedIssue("ZT-BUG-99")).thenReturn(Optional.empty());

        deps.service.processDueEvents();

        assertThat(event.getStatus()).isEqualTo("SUCCESS");
        verify(deps.zenTaoService, never()).fetchBugSnapshot(any());
        verify(deps.issueRepo, never()).save(any());
    }

    @Test
    void doesNotWriteTimelineWhenSnapshotHasNoLocalChanges() {
        Dependencies deps = dependencies();
        ZenTaoWebhookEvent event = event("ZT-BUG-24");
        FeedbackIssue issue = FeedbackIssue.builder()
                .id("ISSUE-24")
                .relatedIssue("ZT-BUG-24")
                .status(IssueStatusEnum.OPEN)
                .severity("HIGH")
                .priority("P2")
                .build();
        when(deps.eventRepo.findByStatusInAndNextRetryAtLessThanEqualOrderByReceivedAtAsc(
                anyCollection(), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(deps.issueRepo.findByRelatedIssue("ZT-BUG-24")).thenReturn(Optional.of(issue));
        when(deps.zenTaoService.fetchBugSnapshot("ZT-BUG-24"))
                .thenReturn(new ZenTaoBugSnapshot("ZT-BUG-24", "active", false, "HIGH", "P2"));

        deps.service.processDueEvents();

        assertThat(event.getStatus()).isEqualTo("SUCCESS");
        verify(deps.issueRepo, never()).save(any());
        verify(deps.timelineRepo, never()).save(any());
    }

    @Test
    void doesNotDowngradeConfirmedFlagFromZenTaoWebhook() {
        Dependencies deps = dependencies();
        ZenTaoWebhookEvent event = event("ZT-BUG-24");
        FeedbackIssue issue = FeedbackIssue.builder()
                .id("ISSUE-24")
                .relatedIssue("ZT-BUG-24")
                .status(IssueStatusEnum.OPEN)
                .confirmed(true)
                .severity("HIGH")
                .priority("P2")
                .build();
        when(deps.eventRepo.findByStatusInAndNextRetryAtLessThanEqualOrderByReceivedAtAsc(
                anyCollection(), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(deps.issueRepo.findByRelatedIssue("ZT-BUG-24")).thenReturn(Optional.of(issue));
        when(deps.zenTaoService.fetchBugSnapshot("ZT-BUG-24"))
                .thenReturn(new ZenTaoBugSnapshot("ZT-BUG-24", "active", false, "HIGH", "P2"));

        deps.service.processDueEvents();

        assertThat(issue.getConfirmed()).isTrue();
        assertThat(event.getStatus()).isEqualTo("SUCCESS");
        verify(deps.issueRepo, never()).save(any());
        verify(deps.timelineRepo, never()).save(any());
    }

    @Test
    void clearsResolvedAtWhenZenTaoActivatesBug() {
        Dependencies deps = dependencies();
        ZenTaoWebhookEvent event = event("ZT-BUG-24");
        FeedbackIssue issue = FeedbackIssue.builder()
                .id("ISSUE-24")
                .relatedIssue("ZT-BUG-24")
                .status(IssueStatusEnum.RESOLVED)
                .resolvedAt(LocalDateTime.now().minusDays(1))
                .severity("HIGH")
                .priority("P2")
                .build();
        when(deps.eventRepo.findByStatusInAndNextRetryAtLessThanEqualOrderByReceivedAtAsc(
                anyCollection(), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(deps.issueRepo.findByRelatedIssue("ZT-BUG-24")).thenReturn(Optional.of(issue));
        when(deps.zenTaoService.fetchBugSnapshot("ZT-BUG-24"))
                .thenReturn(new ZenTaoBugSnapshot("ZT-BUG-24", "active", null, "HIGH", "P2"));

        deps.service.processDueEvents();

        assertThat(issue.getStatus()).isEqualTo(IssueStatusEnum.OPEN);
        assertThat(issue.getResolvedAt()).isNull();
        verify(deps.issueRepo).save(issue);
    }

    private Dependencies dependencies() {
        ZenTaoWebhookEventRepository eventRepo = mock(ZenTaoWebhookEventRepository.class);
        FeedbackIssueRepository issueRepo = mock(FeedbackIssueRepository.class);
        IssueTimelineRepository timelineRepo = mock(IssueTimelineRepository.class);
        ZenTaoService zenTaoService = mock(ZenTaoService.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock("zentao:webhook:worker")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(eventRepo.findByStatusAndUpdatedAtBefore(eq("PROCESSING"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());
        when(eventRepo.save(any(ZenTaoWebhookEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ZenTaoWebhookEventService service = new ZenTaoWebhookEventService(
                eventRepo, issueRepo, timelineRepo, zenTaoService, redisson);
        return new Dependencies(service, eventRepo, issueRepo, timelineRepo, zenTaoService);
    }

    private ZenTaoWebhookEvent event(String issueKey) {
        return ZenTaoWebhookEvent.builder()
                .id(1L)
                .issueKey(issueKey)
                .status("PENDING")
                .retryCount(0)
                .receivedAt(LocalDateTime.now().minusSeconds(1))
                .nextRetryAt(LocalDateTime.now().minusSeconds(1))
                .build();
    }

    private record Dependencies(ZenTaoWebhookEventService service,
                                ZenTaoWebhookEventRepository eventRepo,
                                FeedbackIssueRepository issueRepo,
                                IssueTimelineRepository timelineRepo,
                                ZenTaoService zenTaoService) {
    }
}
