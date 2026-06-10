package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackClaim;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.entity.IssueTimeline;
import com.feedback.analyzer.model.dto.AiAnalysisResult;
import com.feedback.analyzer.model.dto.BugInitialTriageDecision;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.repository.FeedbackAnalyzedRepository;
import com.feedback.analyzer.repository.FeedbackClaimRepository;
import com.feedback.analyzer.repository.FeedbackIssueEsRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import com.feedback.analyzer.repository.IssueTimelineRepository;
import com.feedback.analyzer.service.AiAnalysisService;
import com.feedback.analyzer.service.FeedbackRawIngestionService;
import com.feedback.analyzer.service.IssueRecallService;
import com.feedback.analyzer.service.ZenTaoService;
import com.feedback.analyzer.service.ai.BugInitialTriageService;
import com.feedback.analyzer.service.ai.IssueProgressDecisionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackServiceImplTest {

    @Test
    void linkingClaimDoesNotChangeIssueSeverityOrPriority() {
        Dependencies deps = dependencies();
        FeedbackIssue issue = FeedbackIssue.builder()
                .id("ISSUE-1")
                .productId(1L)
                .title("登录失败")
                .category(FeedbackCategoryEnum.BUG)
                .module("登录")
                .severity("HIGH")
                .priority("P2")
                .status(IssueStatusEnum.OPEN)
                .reportCount(1)
                .firstReportAt(LocalDateTime.now().minusHours(2))
                .latestReportAt(LocalDateTime.now().minusHours(1))
                .aiSummary("登录失败")
                .typicalContent("登录失败")
                .build();
        FeedbackClaim claim = FeedbackClaim.builder()
                .id("FC-1")
                .rawId("RAW-1")
                .analyzedId("FA-1")
                .productId(1L)
                .claimIndex(0)
                .category(FeedbackCategoryEnum.BUG)
                .module("登录")
                .summary("登录失败并且影响所有用户")
                .content("登录失败并且影响所有用户")
                .build();
        Set<String> counted = new HashSet<>();

        deps.service.linkClaimToIssue(claim, issue, counted, true);

        assertThat(issue.getSeverity()).isEqualTo("HIGH");
        assertThat(issue.getPriority()).isEqualTo("P2");
        ArgumentCaptor<FeedbackIssue> savedIssue = ArgumentCaptor.forClass(FeedbackIssue.class);
        verify(deps.issueRepo).save(savedIssue.capture());
        assertThat(savedIssue.getValue().getSeverity()).isEqualTo("HIGH");
        assertThat(savedIssue.getValue().getPriority()).isEqualTo("P2");
        ArgumentCaptor<IssueTimeline> timeline = ArgumentCaptor.forClass(IssueTimeline.class);
        verify(deps.timelineRepo).save(timeline.capture());
        assertThat(timeline.getValue().getEventType()).isEqualTo("feedback_claim_linked");
    }

    @Test
    void deferredBugIssueProgressDoesNotRunForAnySeverity() {
        for (String severity : List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")) {
            Dependencies deps = dependencies();
            FeedbackClaim claim = bugClaim("RAW-" + severity);
            when(deps.bugInitialTriageService.decide(any(FeedbackClaim.class)))
                    .thenReturn(BugInitialTriageDecision.builder()
                            .severity(severity)
                            .priority("P2")
                            .reason("测试定级")
                            .source("AI_INITIAL")
                            .confidence(0.9)
                            .build());
            when(deps.issueRepo.save(any(FeedbackIssue.class))).thenAnswer(invocation -> invocation.getArgument(0));

            deps.service.createNewIssue(claim, List.of(0.1, 0.2, 0.3), true);

            verify(deps.issueProgressDecisionService, never()).progress(any(FeedbackIssue.class));
        }
    }

    @Test
    void ordinaryBugIssueProgressStillRunsWhenNotDeferred() {
        Dependencies deps = dependencies();
        FeedbackClaim claim = bugClaim("RAW-NORMAL");
        when(deps.bugInitialTriageService.decide(any(FeedbackClaim.class)))
                .thenReturn(BugInitialTriageDecision.builder()
                        .severity("HIGH")
                        .priority("P2")
                        .reason("测试定级")
                        .source("AI_INITIAL")
                        .confidence(0.9)
                        .build());
        when(deps.issueRepo.save(any(FeedbackIssue.class))).thenAnswer(invocation -> invocation.getArgument(0));

        deps.service.createNewIssue(claim, List.of(0.1, 0.2, 0.3), false);

        verify(deps.issueProgressDecisionService).progress(any(FeedbackIssue.class));
    }

    @Test
    void aiFailureRevertsAnalyzingRawToRawWithError() throws Exception {
        Dependencies deps = dependencies();
        FeedbackRaw raw = raw("RAW-FAIL", FeedbackStatusEnum.RAW);
        mockRawLookup(deps, raw);
        mockRawLock(deps, raw.getId());
        when(deps.aiService.batchAnalyze(any())).thenThrow(new IllegalStateException("AI 调用失败"));

        deps.service.processRaw(raw.getId());

        assertThat(raw.getStatus()).isEqualTo(FeedbackStatusEnum.RAW);
        assertThat(raw.getProcessingStartedAt()).isNull();
        assertThat(raw.getProcessingError()).contains("AI 调用失败");
    }

    @Test
    void timedOutAnalyzingRawCanRetryToTerminalStatus() throws Exception {
        Dependencies deps = dependencies();
        FeedbackRaw raw = raw("RAW-RETRY", FeedbackStatusEnum.ANALYZING);
        raw.setProcessingStartedAt(LocalDateTime.now().minusHours(2));
        mockRawLookup(deps, raw);
        mockRawLock(deps, raw.getId());
        when(deps.aiService.batchAnalyze(any())).thenReturn(List.of(lowQualityResult()));

        deps.service.retryTimedOutAnalyzing(raw.getId());

        assertThat(raw.getStatus()).isEqualTo(FeedbackStatusEnum.LOW_QUALITY);
        assertThat(raw.getProcessingStartedAt()).isNull();
        assertThat(raw.getProcessingError()).isNull();
    }

    @Test
    void terminalRawDuplicateMessageIsSkipped() throws Exception {
        Dependencies deps = dependencies();
        FeedbackRaw raw = raw("RAW-DONE", FeedbackStatusEnum.ANALYZED);
        mockRawLookup(deps, raw);
        mockRawLock(deps, raw.getId());

        deps.service.processRaw(raw.getId());

        verify(deps.aiService, never()).batchAnalyze(any());
    }

    private FeedbackClaim bugClaim(String rawId) {
        return FeedbackClaim.builder()
                .id("FC-" + rawId)
                .rawId(rawId)
                .analyzedId("FA-" + rawId)
                .productId(1L)
                .claimIndex(0)
                .category(FeedbackCategoryEnum.BUG)
                .module("核心功能")
                .summary("点击后一直转圈，内容无法播放")
                .content("点击后一直转圈，内容无法播放")
                .build();
    }

    private FeedbackRaw raw(String rawId, FeedbackStatusEnum status) {
        return FeedbackRaw.builder()
                .id(rawId)
                .productId(1L)
                .channel("webhook")
                .rawContent("点击后一直转圈，内容无法播放")
                .feedbackTime(LocalDateTime.now())
                .status(status)
                .build();
    }

    private AiAnalysisResult lowQualityResult() {
        AiAnalysisResult result = new AiAnalysisResult();
        result.setIsLowQuality(true);
        result.setCategory("LOW_QUALITY");
        result.setSummary("信息不足");
        result.setClaims(List.of());
        return result;
    }

    private void mockRawLookup(Dependencies deps, FeedbackRaw raw) {
        when(deps.rawRepo.findById(raw.getId())).thenReturn(Optional.of(raw));
        when(deps.rawRepo.save(any(FeedbackRaw.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void mockRawLock(Dependencies deps, String rawId) throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(deps.redisson.getLock("feedback:lock:" + rawId)).thenReturn(lock);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    private Dependencies dependencies() {
        FeedbackRawRepository rawRepo = mock(FeedbackRawRepository.class);
        FeedbackAnalyzedRepository analyzedRepo = mock(FeedbackAnalyzedRepository.class);
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        FeedbackIssueRepository issueRepo = mock(FeedbackIssueRepository.class);
        IssueTimelineRepository timelineRepo = mock(IssueTimelineRepository.class);
        AiAnalysisService aiService = mock(AiAnalysisService.class);
        RedissonClient redisson = mock(RedissonClient.class);
        FeedbackIssueEsRepository issueEsRepo = mock(FeedbackIssueEsRepository.class);
        IssueRecallService issueRecallService = mock(IssueRecallService.class);
        BugInitialTriageService bugInitialTriageService = mock(BugInitialTriageService.class);
        IssueProgressDecisionService issueProgressDecisionService = mock(IssueProgressDecisionService.class);
        ZenTaoService zenTaoService = mock(ZenTaoService.class);
        PublicReviewRunCoordinator publicReviewRunCoordinator = mock(PublicReviewRunCoordinator.class);
        FeedbackRawIngestionService rawIngestionService = mock(FeedbackRawIngestionService.class);
        FeedbackServiceImpl service = new FeedbackServiceImpl(
                rawRepo,
                analyzedRepo,
                claimRepo,
                issueRepo,
                timelineRepo,
                aiService,
                redisson,
                issueEsRepo,
                issueRecallService,
                bugInitialTriageService,
                issueProgressDecisionService,
                zenTaoService,
                publicReviewRunCoordinator,
                rawIngestionService,
                new ObjectMapper(),
                transactionManager());
        return new Dependencies(
                service,
                rawRepo,
                issueRepo,
                timelineRepo,
                aiService,
                redisson,
                bugInitialTriageService,
                issueProgressDecisionService);
    }

    private PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }

    private record Dependencies(FeedbackServiceImpl service,
                                FeedbackRawRepository rawRepo,
                                FeedbackIssueRepository issueRepo,
                                IssueTimelineRepository timelineRepo,
                                AiAnalysisService aiService,
                                RedissonClient redisson,
                                BugInitialTriageService bugInitialTriageService,
                                IssueProgressDecisionService issueProgressDecisionService) {
    }
}
