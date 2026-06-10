package com.feedback.analyzer.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackAnalyzed;
import com.feedback.analyzer.entity.FeedbackClaim;
import com.feedback.analyzer.model.dto.BugInitialTriageDecision;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.service.AiRateLimiter;
import com.feedback.analyzer.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class BugInitialTriageService {

    private final ChatClient.Builder chatClientBuilder;
    private final PromptTemplateService promptTemplateService;
    private final AiRateLimiter aiRateLimiter;
    private final ObjectMapper objectMapper;

    public BugInitialTriageDecision decide(FeedbackClaim claim) {
        if (claim == null || claim.getCategory() != FeedbackCategoryEnum.BUG) {
            return BugInitialTriageDecision.systemDefault("仅 Bug 问题使用初始定级");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("category", claim.getCategory().name());
        context.put("module", value(claim.getModule()));
        context.put("summary", value(claim.getSummary()));
        context.put("content", value(claim.getContent()));
        context.put("keywords", value(claim.getKeywords()));
        return decide(context);
    }

    public BugInitialTriageDecision decide(FeedbackAnalyzed analyzed) {
        if (analyzed == null || analyzed.getCategory() != FeedbackCategoryEnum.BUG) {
            return BugInitialTriageDecision.systemDefault("仅 Bug 问题使用初始定级");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("category", analyzed.getCategory().name());
        context.put("module", value(analyzed.getModule()));
        context.put("summary", value(analyzed.getSummary()));
        context.put("content", value(analyzed.getSummary()));
        context.put("keywords", value(analyzed.getKeywords()));
        return decide(context);
    }

    private BugInitialTriageDecision decide(Map<String, Object> context) {
        try {
            aiRateLimiter.acquireLlm();
            String response = chatClientBuilder.build().prompt()
                    .system(promptTemplateService.load("bug-initial-triage.md"))
                    .user(objectMapper.writeValueAsString(context))
                    .call()
                    .content();
            JsonNode node = parseJsonObject(response);
            if (node == null) {
                return BugInitialTriageDecision.systemDefault("AI 初始定级返回格式无效");
            }
            return sanitize(node);
        } catch (Exception e) {
            log.warn("Bug initial triage failed", e);
            return BugInitialTriageDecision.systemDefault("AI 初始定级失败，系统已回退默认等级");
        }
    }

    private BugInitialTriageDecision sanitize(JsonNode node) {
        String severity = normalizeSeverity(text(node, "severity"));
        String priority = normalizePriority(text(node, "priority"));
        double confidence = clampConfidence(node.has("confidence") ? node.get("confidence").asDouble(0.0) : 0.0);
        String reason = text(node, "reason");
        if (severity == null || priority == null) {
            return BugInitialTriageDecision.systemDefault("AI 初始定级返回了不支持的严重度或优先级");
        }
        return BugInitialTriageDecision.builder()
                .severity(severity)
                .priority(priority)
                .confidence(confidence)
                .reason(reason == null || reason.isBlank() ? "AI 已给出初始等级建议。" : reason.trim())
                .source("AI_INITIAL")
                .build();
    }

    private JsonNode parseJsonObject(String content) {
        try {
            if (content == null) {
                return null;
            }
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            return objectMapper.readTree(content.substring(start, end + 1));
        } catch (Exception e) {
            log.warn("Failed to parse bug initial triage JSON: {}", content, e);
            return null;
        }
    }

    private String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return null;
        }
        String value = severity.trim().toUpperCase(Locale.ROOT);
        return Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW").contains(value) ? value : null;
    }

    private String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return null;
        }
        String value = priority.trim().toUpperCase(Locale.ROOT);
        return Set.of("P1", "P2", "P3", "P4").contains(value) ? value : null;
    }

    private double clampConfidence(double confidence) {
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("");
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
