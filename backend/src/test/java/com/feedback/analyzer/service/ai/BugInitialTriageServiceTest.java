package com.feedback.analyzer.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackClaim;
import com.feedback.analyzer.model.dto.BugInitialTriageDecision;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.service.AiRateLimiter;
import com.feedback.analyzer.service.PromptTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BugInitialTriageServiceTest {

    @Test
    void returnsAiInitialTriageDecision() {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        AiRateLimiter aiRateLimiter = mock(AiRateLimiter.class);
        when(promptTemplateService.load("bug-initial-triage.md")).thenReturn("triage prompt");
        when(chatClientBuilder.build().prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("""
                        {
                          "severity": "HIGH",
                          "priority": "P2",
                          "confidence": 0.78,
                          "reason": "Payment confirmation crashes."
                        }
                        """);
        BugInitialTriageService service = new BugInitialTriageService(
                chatClientBuilder, promptTemplateService, aiRateLimiter, new ObjectMapper());

        BugInitialTriageDecision decision = service.decide(FeedbackClaim.builder()
                .category(FeedbackCategoryEnum.BUG)
                .module("支付")
                .summary("支付确认后崩溃")
                .content("点击支付确认后页面崩溃")
                .build());

        assertThat(decision.getSeverity()).isEqualTo("HIGH");
        assertThat(decision.getPriority()).isEqualTo("P2");
        assertThat(decision.getSource()).isEqualTo("AI_INITIAL");
        assertThat(decision.getConfidence()).isEqualTo(0.78);
    }

    @Test
    void fallsBackWhenAiReturnsInvalidJson() {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        AiRateLimiter aiRateLimiter = mock(AiRateLimiter.class);
        when(promptTemplateService.load("bug-initial-triage.md")).thenReturn("triage prompt");
        when(chatClientBuilder.build().prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("invalid");
        BugInitialTriageService service = new BugInitialTriageService(
                chatClientBuilder, promptTemplateService, aiRateLimiter, new ObjectMapper());

        BugInitialTriageDecision decision = service.decide(FeedbackClaim.builder()
                .category(FeedbackCategoryEnum.BUG)
                .summary("启动失败")
                .build());

        assertThat(decision.getSeverity()).isEqualTo("MEDIUM");
        assertThat(decision.getPriority()).isEqualTo("P3");
        assertThat(decision.getSource()).isEqualTo("SYSTEM_DEFAULT");
    }
}
