package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.model.dto.AiAnalysisResult;
import com.feedback.analyzer.service.AiAnalysisService;
import com.feedback.analyzer.service.AiRateLimiter;
import com.feedback.analyzer.service.PromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

@Slf4j
@Service
public class AiAnalysisServiceImpl implements AiAnalysisService {

    private final ChatClient chatClient;
    private final RestClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final String embeddingModelName;
    private final PromptTemplateService promptTemplateService;
    private final AiRateLimiter aiRateLimiter;

    public AiAnalysisServiceImpl(ChatClient.Builder chatClientBuilder,
                                  @Value("${embedding.api-url}") String embeddingApiUrl,
                                  @Value("${embedding.api-key}") String embeddingApiKey,
                                  @Value("${embedding.model}") String embeddingModelName,
                                  ObjectMapper objectMapper,
                                  PromptTemplateService promptTemplateService,
                                  AiRateLimiter aiRateLimiter) {
        this.chatClient = chatClientBuilder.build();
        this.embeddingModelName = embeddingModelName;
        this.objectMapper = objectMapper;
        this.promptTemplateService = promptTemplateService;
        this.aiRateLimiter = aiRateLimiter;
        this.embeddingClient = RestClient.builder()
                .baseUrl(embeddingApiUrl)
                .defaultHeader("Authorization", "Bearer " + embeddingApiKey)
                .build();
    }

    @Override
    public List<AiAnalysisResult> batchAnalyze(List<Map<String, String>> feedbackBatch) {
        if (feedbackBatch == null || feedbackBatch.isEmpty()) {
            return List.of();
        }

        StringBuilder userContent = new StringBuilder();
        for (int i = 0; i < feedbackBatch.size(); i++) {
            Map<String, String> fb = feedbackBatch.get(i);
            userContent.append("[").append(i).append("] ")
                    .append("内容: ").append(fb.getOrDefault("content", ""))
                    .append(" | 版本: ").append(fb.getOrDefault("version", ""))
                    .append(" | 设备: ").append(fb.getOrDefault("device", ""))
                    .append("\n");
        }

        try {
            aiRateLimiter.acquireLlm();
            String response = chatClient.prompt()
                    .system(promptTemplateService.load("feedback-analysis.md"))
                    .user(userContent.toString())
                    .call()
                    .content();

            String jsonStr = extractJsonArray(response);
            return objectMapper.readValue(jsonStr, new TypeReference<List<AiAnalysisResult>>() {});
        } catch (Exception e) {
            log.error("LLM analysis failed", e);
            return buildFallback(feedbackBatch.size());
        }
    }

    @Override
    public List<Double> generateEmbedding(String text) {
        try {
            aiRateLimiter.acquireEmbedding();
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", embeddingModelName);
            requestBody.put("input", Map.of("texts", List.of(text)));
            requestBody.put("parameters", Map.of("text_type", "query"));

            Map<String, Object> result = embeddingClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

            if (result != null) {
                Map<String, Object> output = (Map<String, Object>) result.get("output");
                if (output != null) {
                    List<Map<String, Object>> embeddings = (List<Map<String, Object>>) output.get("embeddings");
                    if (embeddings != null && !embeddings.isEmpty()) {
                        Object embeddingObj = embeddings.get(0).get("embedding");
                        if (embeddingObj instanceof List) {
                            return (List<Double>) embeddingObj;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Embedding generation failed", e);
        }
        log.warn("Embedding generation failed, dedup will be skipped");
        return List.of();
    }

    private String extractJsonArray(String content) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return "[]";
    }

    private List<AiAnalysisResult> buildFallback(int count) {
        List<AiAnalysisResult> fallback = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            AiAnalysisResult result = new AiAnalysisResult();
            result.setCategory("SUGGESTION");
            result.setSummary("(分析失败)");
            com.feedback.analyzer.model.dto.AiClaimResult claim = new com.feedback.analyzer.model.dto.AiClaimResult();
            claim.setCategory("SUGGESTION");
            claim.setSummary("(分析失败)");
            claim.setIsPrimary(true);
            result.setClaims(List.of(claim));
            result.setIsLowQuality(false);
            fallback.add(result);
        }
        return fallback;
    }
}
