package com.feedback.analyzer.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.model.dto.AiIssueDecision;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.repository.FeedbackAnalyzedRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.IssueTimelineRepository;
import com.feedback.analyzer.service.AiRateLimiter;
import com.feedback.analyzer.service.PromptTemplateService;
import com.feedback.analyzer.service.ZenTaoService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueProgressDecisionServiceTest {

    @Test
    void skipsRealtimeFeishuForCriticalBug() {
        Dependencies deps = dependencies();

        AiIssueDecision decision = deps.ai.progress(criticalIssue());

        assertThat(decision.isNotificationSent()).isFalse();
        assertThat(decision.getNotificationReason()).contains("飞书仅用于周报");
        assertThat(decision.getNotificationMessage()).isEmpty();
        verify(deps.chatClientBuilder, times(1)).build();
    }

    @Test
    void skipsRealtimeFeishuForHighBug() {
        Dependencies deps = dependencies();

        AiIssueDecision decision = deps.ai.progress(issue("HIGH", FeedbackCategoryEnum.BUG));

        assertThat(decision.isNotificationSent()).isFalse();
        assertThat(decision.getNotificationReason()).contains("飞书仅用于周报");
    }

    @Test
    void createsZenTaoBugForNonCriticalBugWithoutFeishu() {
        Dependencies deps = dependencies();
        FeedbackIssue mediumBug = issue("MEDIUM", FeedbackCategoryEnum.BUG);
        when(deps.zenTaoService.createBug(any(FeedbackIssue.class), anyString(), anyString())).thenReturn("BUG-1001");

        AiIssueDecision decision = deps.ai.progress(mediumBug);

        assertThat(decision.getZentaoAction()).isEqualTo("CREATE_BUG");
        assertThat(decision.getZentaoIssueKey()).isEqualTo("BUG-1001");
        assertThat(decision.isNotificationSent()).isFalse();
    }

    @Test
    void createsZenTaoBugForBugWithoutOldKeywordSignals() {
        Dependencies deps = dependencies();
        FeedbackIssue gameplayBug = issue("MEDIUM", FeedbackCategoryEnum.BUG);
        gameplayBug.setModule("场景系统");
        gameplayBug.setTitle("空气墙导致角色神游");
        gameplayBug.setAiSummary("场景中存在空气墙，角色会被卡进异常位置");
        gameplayBug.setTypicalContent("角色靠近边缘后被空气墙卡住，随后神游到地图外");
        when(deps.zenTaoService.createBug(any(FeedbackIssue.class), anyString(), anyString())).thenReturn("BUG-1002");

        AiIssueDecision decision = deps.ai.progress(gameplayBug);

        assertThat(decision.getZentaoAction()).isEqualTo("CREATE_BUG");
        assertThat(decision.getZentaoIssueKey()).isEqualTo("BUG-1002");
        verify(deps.zenTaoService).createBug(any(FeedbackIssue.class), anyString(), anyString());
    }

    @Test
    void appendsZenTaoCommentForLinkedBugWithoutCreatingAgain() {
        Dependencies deps = dependencies();
        FeedbackIssue linkedBug = issue("HIGH", FeedbackCategoryEnum.BUG);
        linkedBug.setRelatedIssue("ZT-BUG-9");
        when(deps.zenTaoService.syncBugUpdate(any(FeedbackIssue.class), anyString())).thenReturn(true);

        AiIssueDecision decision = deps.ai.progress(linkedBug);

        assertThat(decision.getZentaoAction()).isEqualTo("APPEND_COMMENT");
        assertThat(decision.getZentaoIssueKey()).isEqualTo("ZT-BUG-9");
        verify(deps.zenTaoService, never()).createBug(any(FeedbackIssue.class), anyString(), anyString());
        verify(deps.zenTaoService).syncBugUpdate(any(FeedbackIssue.class), anyString());
    }

    @Test
    void skipsZenTaoForNonBugIssue() {
        Dependencies deps = dependencies();

        AiIssueDecision decision = deps.ai.progress(issue("CRITICAL", FeedbackCategoryEnum.SUGGESTION));

        assertThat(decision.getZentaoAction()).isEqualTo("NOOP");
        assertThat(decision.isNotificationSent()).isFalse();
        verify(deps.zenTaoService, never()).createBug(any(FeedbackIssue.class), anyString(), anyString());
        verify(deps.zenTaoService, never()).syncBugUpdate(any(FeedbackIssue.class), anyString());
    }

    private Dependencies dependencies() {
        FeedbackAnalyzedRepository analyzedRepo = mock(FeedbackAnalyzedRepository.class);
        FeedbackIssueRepository issueRepo = mock(FeedbackIssueRepository.class);
        IssueTimelineRepository timelineRepo = mock(IssueTimelineRepository.class);
        ZenTaoService zenTaoService = mock(ZenTaoService.class);
        AiRunLogger runLogger = mock(AiRunLogger.class);
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        AiRateLimiter aiRateLimiter = mock(AiRateLimiter.class);

        when(timelineRepo.findByIssueIdOrderByCreatedAtDesc("ISSUE-1")).thenReturn(List.of());
        when(chatClientBuilder.build().prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("""
                        {
                          "zentaoAction": "NOOP",
                          "reason": "保持当前处理路径"
                        }
                        """);
        when(promptTemplateService.load("issue-progress.md")).thenReturn("issue progress prompt");
        clearInvocations(chatClientBuilder);

        IssueProgressDecisionService ai = new IssueProgressDecisionService(
                analyzedRepo, issueRepo, timelineRepo, zenTaoService,
                runLogger, new ObjectMapper(), chatClientBuilder, promptTemplateService, aiRateLimiter);
        return new Dependencies(ai, zenTaoService, chatClientBuilder);
    }

    private FeedbackIssue criticalIssue() {
        return issue("CRITICAL", FeedbackCategoryEnum.BUG);
    }

    private FeedbackIssue issue(String severity, FeedbackCategoryEnum category) {
        return FeedbackIssue.builder()
                .id("ISSUE-1")
                .productId(1L)
                .title("支付流程崩溃")
                .category(category)
                .module("支付")
                .severity(severity)
                .status(IssueStatusEnum.OPEN)
                .reportCount(4)
                .aiSummary("支付流程崩溃")
                .typicalContent("用户点击支付确认后页面崩溃")
                .build();
    }

    private record Dependencies(IssueProgressDecisionService ai,
                                ZenTaoService zenTaoService,
                                ChatClient.Builder chatClientBuilder) {
    }
}
