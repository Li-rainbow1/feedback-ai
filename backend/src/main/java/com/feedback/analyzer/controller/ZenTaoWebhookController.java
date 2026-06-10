package com.feedback.analyzer.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.model.ApiResult;
import com.feedback.analyzer.service.impl.ZenTaoWebhookEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@RestController
@RequestMapping("/api/v1/zentao")
public class ZenTaoWebhookController {

    private final ZenTaoWebhookEventService eventService;
    private final ObjectMapper objectMapper;
    private final String webhookToken;

    public ZenTaoWebhookController(ZenTaoWebhookEventService eventService,
                                   ObjectMapper objectMapper,
                                   @Value("${zentao.webhook-token:}") String webhookToken) {
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.webhookToken = webhookToken;
    }

    @PostMapping("/webhook")
    public ApiResult<Void> handleWebhook(
            @RequestHeader(value = "X-ZenTao-Webhook-Token", required = false) String requestToken,
            @RequestParam(value = "token", required = false) String queryToken,
            @RequestBody String payload) {
        verifyWebhookToken(requestToken != null && !requestToken.isBlank() ? requestToken : queryToken);

        JsonNode root = parsePayload(payload);
        String issueKey = extractIssueKey(root);
        if (issueKey == null) {
            log.debug("ZenTao webhook skipped because bug id is missing");
            return ApiResult.success();
        }

        eventService.record(root, payload, issueKey);
        log.info("ZenTao webhook event accepted: {}", issueKey);
        return ApiResult.success();
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            log.error("ZenTao webhook parse error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "禅道回调内容解析失败");
        }
    }

    private String extractIssueKey(JsonNode root) {
        JsonNode object = nestedObject(root);
        String oldId = firstText(object, "id", "ID", "bugId", "bugID");
        if (oldId != null) {
            return "ZT-BUG-" + oldId;
        }

        String objectType = firstText(root, "objectType", "object_type");
        if (objectType != null && !"bug".equalsIgnoreCase(objectType.trim())) {
            return null;
        }
        String objectId = firstText(root, "objectID", "objectId", "bugID", "bugId", "id");
        return objectId == null ? null : "ZT-BUG-" + objectId;
    }

    private JsonNode nestedObject(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.has("object")) {
            return root.get("object");
        }
        if (root.has("data")) {
            return root.get("data");
        }
        return null;
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asText();
            }
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
            if (value.isObject()) {
                String nested = firstText(value, "id", "value", "code", "name", "title", "label");
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private void verifyWebhookToken(String requestToken) {
        if (webhookToken == null || webhookToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "禅道回调 Token 未配置");
        }
        if (requestToken == null || !MessageDigest.isEqual(
                webhookToken.getBytes(StandardCharsets.UTF_8),
                requestToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "禅道回调 Token 校验失败");
        }
    }
}
