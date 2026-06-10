package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.Product;
import com.feedback.analyzer.repository.ProductRepository;
import com.feedback.analyzer.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    private static final int FEISHU_CONNECT_TIMEOUT_MS = 5_000;
    private static final int FEISHU_READ_TIMEOUT_MS = 10_000;
    private static final int FEISHU_MARKDOWN_LIMIT = 6_000;
    private static final String FEISHU_TRUNCATED_SUFFIX = "\n\n> 内容较长，已截断，请在系统查看完整周报。";

    private final String defaultFeishuUrl;
    private final ObjectMapper objectMapper;
    private final ProductRepository productRepo;

    public NotificationServiceImpl(
            @Value("${notification.feishu.webhook-url:}") String feishuUrl,
            ObjectMapper objectMapper,
            ProductRepository productRepo) {
        this.defaultFeishuUrl = feishuUrl;
        this.objectMapper = objectMapper;
        this.productRepo = productRepo;
        log.info("Notification init: defaultFeishuConfigured={}", !feishuUrl.isBlank());
    }

    @Override
    public boolean notifyReportReady(Long productId, Long reportId, String weekRange, String content) {
        String productName = productId != null
                ? productRepo.findById(productId).map(Product::getName).orElse("未命名产品")
                : "未命名产品";
        String title = productName + " " + weekRange + " 周报";
        String markdown = content != null && !content.isBlank() ? content : "本周暂无可展示内容。";
        return sendFeishuCard(productId, title, truncateForFeishu(formatReportForFeishu(markdown)));
    }

    private boolean sendFeishuCard(Long productId, String title, String markdown) {
        Map<String, Object> card = Map.of(
                "config", Map.of("wide_screen_mode", true),
                "header", Map.of(
                        "template", "blue",
                        "title", Map.of("tag", "plain_text", "content", title)
                ),
                "elements", List.of(Map.of(
                        "tag", "div",
                        "text", Map.of("tag", "lark_md", "content", markdown)
                ))
        );
        return sendFeishuPayload(productId, Map.of("msg_type", "interactive", "card", card));
    }

    private String formatReportForFeishu(String markdown) {
        StringBuilder content = new StringBuilder();
        String normalized = markdown.replace("\r\n", "\n");
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.matches("^#{1,6}\\s+.+")) {
                String heading = trimmed.replaceFirst("^#{1,6}\\s+", "");
                content.append("**").append(heading).append("**").append('\n');
            } else if (trimmed.startsWith("- ")) {
                content.append("• ").append(trimmed.substring(2)).append('\n');
            } else {
                content.append(line).append('\n');
            }
        }
        return content.toString().strip();
    }

    private String truncateForFeishu(String markdown) {
        if (markdown == null || markdown.length() <= FEISHU_MARKDOWN_LIMIT) {
            return markdown;
        }
        int maxContentLength = Math.max(0, FEISHU_MARKDOWN_LIMIT - FEISHU_TRUNCATED_SUFFIX.length());
        return markdown.substring(0, maxContentLength).stripTrailing() + FEISHU_TRUNCATED_SUFFIX;
    }

    private boolean sendFeishuPayload(Long productId, Map<String, Object> payload) {
        RestClient feishuClient = clientFor(productId);
        if (feishuClient == null) {
            log.info("Feishu not configured for product {}", productId);
            return false;
        }
        try {
            String resp = feishuClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            JsonNode result = objectMapper.readTree(resp);
            int code = result.has("code") ? result.path("code").asInt(-1) : result.path("StatusCode").asInt(-1);
            if (code != 0) {
                log.warn("Feishu rejected message: code={}, message={}", code,
                        result.has("msg") ? result.path("msg").asText() : result.path("StatusMessage").asText());
                return false;
            }
            log.info("Feishu sent OK");
            return true;
        } catch (Exception e) {
            log.error("Feishu failed: {}", e.getMessage());
            return false;
        }
    }

    private RestClient clientFor(Long productId) {
        Product product = productId != null ? productRepo.findById(productId).orElse(null) : null;
        if (product != null) {
            if (Boolean.TRUE.equals(product.getFeishuEnabled())
                    && product.getFeishuWebhookUrl() != null
                    && !product.getFeishuWebhookUrl().isBlank()) {
                return feishuClient(product.getFeishuWebhookUrl());
            }
            return null;
        }
        return defaultFeishuUrl == null || defaultFeishuUrl.isBlank()
                ? null : feishuClient(defaultFeishuUrl);
    }

    private RestClient feishuClient(String webhookUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(FEISHU_CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(FEISHU_READ_TIMEOUT_MS);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(webhookUrl)
                .build();
    }
}
