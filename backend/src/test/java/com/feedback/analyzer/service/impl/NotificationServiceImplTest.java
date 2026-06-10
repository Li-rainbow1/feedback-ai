package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.feedback.analyzer.entity.Product;
import com.feedback.analyzer.repository.ProductRepository;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationServiceImplTest {

    @Test
    void sendsWeeklyReportAsMarkdownCard() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"code\":0,\"msg\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            ProductRepository productRepo = mock(ProductRepository.class);
            when(productRepo.findById(1L)).thenReturn(Optional.of(Product.builder()
                    .id(1L)
                    .name("黑神话悟空")
                    .feishuEnabled(true)
                    .feishuWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build()));
            NotificationServiceImpl service = new NotificationServiceImpl(
                    "",
                    objectMapper,
                    productRepo);

            boolean sent = service.notifyReportReady(
                    1L, 1L, "2026-05-18 ~ 2026-05-24", "## 一、本周核心数据\n\n- **总反馈量**：0");

            JsonNode payload = objectMapper.readTree(requestBody.get());
            assertThat(sent).isTrue();
            assertThat(payload.path("msg_type").asText()).isEqualTo("interactive");
            assertThat(payload.at("/card/header/title/content").asText()).isEqualTo("黑神话悟空 2026-05-18 ~ 2026-05-24 周报");
            assertThat(payload.at("/card/elements/0/tag").asText()).isEqualTo("div");
            assertThat(payload.at("/card/elements/0/text/tag").asText()).isEqualTo("lark_md");
            assertThat(payload.at("/card/elements/0/text/content").asText())
                    .contains("**一、本周核心数据**")
                    .contains("• **总反馈量**：0")
                    .doesNotContain("##");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void truncatesLongWeeklyReportBeforeSendingCard() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"code\":0,\"msg\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            ProductRepository productRepo = mock(ProductRepository.class);
            when(productRepo.findById(1L)).thenReturn(Optional.of(Product.builder()
                    .id(1L)
                    .name("黑神话悟空")
                    .feishuEnabled(true)
                    .feishuWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build()));
            NotificationServiceImpl service = new NotificationServiceImpl(
                    "",
                    objectMapper,
                    productRepo);

            boolean sent = service.notifyReportReady(
                    1L, 1L, "2026-05-18 ~ 2026-05-24", "## 标题\n\n" + "内容".repeat(4_000));

            JsonNode payload = objectMapper.readTree(requestBody.get());
            String content = payload.at("/card/elements/0/text/content").asText();
            assertThat(sent).isTrue();
            assertThat(content).hasSizeLessThanOrEqualTo(6_000);
            assertThat(content).contains("内容较长，已截断，请在系统查看完整周报。");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void productWithMissingWebhookDoesNotUseDefaultWebhook() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"code\":0,\"msg\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ProductRepository productRepo = mock(ProductRepository.class);
            when(productRepo.findById(1L)).thenReturn(Optional.of(Product.builder()
                    .id(1L)
                    .name("黑神话悟空")
                    .feishuEnabled(true)
                    .feishuWebhookUrl("")
                    .build()));
            NotificationServiceImpl service = new NotificationServiceImpl(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    new ObjectMapper(),
                    productRepo);

            boolean sent = service.notifyReportReady(
                    1L, 1L, "2026-05-18 ~ 2026-05-24", "## 一、本周核心数据");

            assertThat(sent).isFalse();
            assertThat(requestBody.get()).isNull();
        } finally {
            server.stop(0);
        }
    }
}
