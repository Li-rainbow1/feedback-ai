package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.entity.IssueTimeline;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.repository.FeedbackAnalyzedRepository;
import com.feedback.analyzer.repository.FeedbackClaimRepository;
import com.feedback.analyzer.repository.FeedbackIssueEsRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import com.feedback.analyzer.repository.IssueTimelineRepository;
import com.feedback.analyzer.service.ZenTaoService;
import com.feedback.analyzer.service.ai.IssueProgressDecisionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueServiceImplTest {

    @Test
    void updatesLocalTriageAfterZenTaoSuccess() {
        Dependencies deps = dependencies();
        FeedbackIssue issue = linkedBug();
        when(deps.issueRepo.findById("ISSUE-1")).thenReturn(Optional.of(issue));
        when(deps.zenTaoService.updateBugTriage(issue)).thenReturn(true);

        deps.service.updateTriage("ISSUE-1", "CRITICAL", "P1", "人工确认数据丢失");

        assertThat(issue.getSeverity()).isEqualTo("CRITICAL");
        assertThat(issue.getPriority()).isEqualTo("P1");
        assertThat(issue.getTriageSource()).isEqualTo("SYSTEM_MANUAL");
        verify(deps.zenTaoService).updateBugTriage(issue);
        verify(deps.issueRepo).save(issue);
        ArgumentCaptor<IssueTimeline> timeline = ArgumentCaptor.forClass(IssueTimeline.class);
        verify(deps.timelineRepo).save(timeline.capture());
        assertThat(timeline.getValue().getEventType()).isEqualTo("triage_updated");
    }

    @Test
    void doesNotSaveLocalTriageWhenZenTaoFails() {
        Dependencies deps = dependencies();
        FeedbackIssue issue = linkedBug();
        when(deps.issueRepo.findById("ISSUE-1")).thenReturn(Optional.of(issue));
        when(deps.zenTaoService.updateBugTriage(issue)).thenReturn(false);

        assertThatThrownBy(() -> deps.service.updateTriage("ISSUE-1", "CRITICAL", "P1", "人工确认数据丢失"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禅道等级同步失败");

        verify(deps.issueRepo, never()).save(issue);
        verify(deps.timelineRepo, never()).save(org.mockito.ArgumentMatchers.any(IssueTimeline.class));
    }

    @Test
    void clearsResolvedAtWhenResolvedBugIsReopened() {
        Dependencies deps = dependencies();
        FeedbackIssue issue = linkedBug();
        issue.setStatus(IssueStatusEnum.RESOLVED);
        issue.setResolvedAt(LocalDateTime.now().minusDays(1));
        when(deps.issueRepo.findById("ISSUE-1")).thenReturn(Optional.of(issue));
        when(deps.zenTaoService.updateBugStatus(issue, IssueStatusEnum.OPEN)).thenReturn(true);

        deps.service.updateStatus("ISSUE-1", IssueStatusEnum.OPEN);

        assertThat(issue.getStatus()).isEqualTo(IssueStatusEnum.OPEN);
        assertThat(issue.getResolvedAt()).isNull();
        verify(deps.zenTaoService).updateBugStatus(issue, IssueStatusEnum.OPEN);
        verify(deps.issueRepo).save(issue);
    }

    @Test
    void treatsLegacyAcknowledgedStatusAsBugConfirm() {
        Dependencies deps = dependencies();
        FeedbackIssue issue = linkedBug();
        issue.setConfirmed(false);
        when(deps.issueRepo.findById("ISSUE-1")).thenReturn(Optional.of(issue));
        when(deps.zenTaoService.confirmBug(issue)).thenReturn(true);

        deps.service.updateStatus("ISSUE-1", IssueStatusEnum.ACKNOWLEDGED);

        assertThat(issue.getStatus()).isEqualTo(IssueStatusEnum.OPEN);
        assertThat(issue.getConfirmed()).isTrue();
        verify(deps.zenTaoService).confirmBug(issue);
        verify(deps.zenTaoService, never()).updateBugStatus(issue, IssueStatusEnum.ACKNOWLEDGED);
        verify(deps.issueRepo).save(issue);
    }

    @Test
    void confirmsLinkedBugAfterZenTaoSuccess() {
        Dependencies deps = dependencies();
        FeedbackIssue issue = linkedBug();
        issue.setConfirmed(false);
        when(deps.issueRepo.findById("ISSUE-1")).thenReturn(Optional.of(issue));
        when(deps.zenTaoService.confirmBug(issue)).thenReturn(true);

        deps.service.confirmIssue("ISSUE-1");

        assertThat(issue.getConfirmed()).isTrue();
        verify(deps.zenTaoService).confirmBug(issue);
        verify(deps.issueRepo).save(issue);
        verify(deps.timelineRepo, times(1)).save(org.mockito.ArgumentMatchers.any(IssueTimeline.class));
    }

    @Test
    void doesNotDowngradeConfirmedBug() {
        Dependencies deps = dependencies();
        FeedbackIssue issue = linkedBug();
        issue.setConfirmed(true);
        when(deps.issueRepo.findById("ISSUE-1")).thenReturn(Optional.of(issue));

        deps.service.confirmIssue("ISSUE-1");

        verify(deps.zenTaoService, never()).confirmBug(issue);
        verify(deps.issueRepo, never()).save(issue);
        verify(deps.timelineRepo, never()).save(org.mockito.ArgumentMatchers.any(IssueTimeline.class));
    }

    private FeedbackIssue linkedBug() {
        return FeedbackIssue.builder()
                .id("ISSUE-1")
                .productId(1L)
                .title("支付失败")
                .category(FeedbackCategoryEnum.BUG)
                .module("支付")
                .severity("HIGH")
                .priority("P2")
                .status(IssueStatusEnum.OPEN)
                .reportCount(2)
                .firstReportAt(LocalDateTime.now().minusHours(2))
                .latestReportAt(LocalDateTime.now().minusHours(1))
                .relatedIssue("ZT-BUG-9")
                .build();
    }

    private Dependencies dependencies() {
        FeedbackIssueRepository issueRepo = mock(FeedbackIssueRepository.class);
        FeedbackIssueEsRepository issueEsRepo = mock(FeedbackIssueEsRepository.class);
        IssueTimelineRepository timelineRepo = mock(IssueTimelineRepository.class);
        FeedbackAnalyzedRepository analyzedRepo = mock(FeedbackAnalyzedRepository.class);
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        FeedbackRawRepository rawRepo = mock(FeedbackRawRepository.class);
        IssueProgressDecisionService issueProgressDecisionService = mock(IssueProgressDecisionService.class);
        ZenTaoService zenTaoService = mock(ZenTaoService.class);
        IssueServiceImpl service = new IssueServiceImpl(
                issueRepo,
                issueEsRepo,
                timelineRepo,
                analyzedRepo,
                claimRepo,
                rawRepo,
                issueProgressDecisionService,
                zenTaoService,
                new ObjectMapper());
        return new Dependencies(service, issueRepo, timelineRepo, zenTaoService);
    }

    private record Dependencies(IssueServiceImpl service,
                                FeedbackIssueRepository issueRepo,
                                IssueTimelineRepository timelineRepo,
                                ZenTaoService zenTaoService) {
    }
}
