package com.feedback.analyzer.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.service.impl.ZenTaoWebhookEventService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ZenTaoWebhookControllerTest {

    @Test
    void rejectsCallbackWithInvalidToken() {
        ZenTaoWebhookEventService eventService = mock(ZenTaoWebhookEventService.class);
        ZenTaoWebhookController controller = new ZenTaoWebhookController(
                eventService, new ObjectMapper(), "callback-secret");

        assertThatThrownBy(() -> controller.handleWebhook("wrong-token", null,
                "{\"object\":{\"id\":15,\"status\":\"resolved\"}}"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(eventService, never()).record(any(JsonNode.class), any(), any());
    }

    @Test
    void recordsOldPayloadAndReturnsImmediately() {
        ZenTaoWebhookEventService eventService = mock(ZenTaoWebhookEventService.class);
        ZenTaoWebhookController controller = new ZenTaoWebhookController(
                eventService, new ObjectMapper(), "callback-secret");
        String payload = "{\"object\":{\"id\":15,\"status\":\"resolved\"}}";

        controller.handleWebhook(null, "callback-secret", payload);

        verify(eventService).record(any(JsonNode.class), eq(payload), eq("ZT-BUG-15"));
    }

    @Test
    void recordsRealZenTaoPayloadAndReturnsImmediately() {
        ZenTaoWebhookEventService eventService = mock(ZenTaoWebhookEventService.class);
        ZenTaoWebhookController controller = new ZenTaoWebhookController(
                eventService, new ObjectMapper(), "callback-secret");
        String payload = "{\"objectType\":\"bug\",\"objectID\":24,\"action\":\"edited\",\"text\":\"admin编辑了Bug\"}";

        controller.handleWebhook(null, "callback-secret", payload);

        verify(eventService).record(any(JsonNode.class), eq(payload), eq("ZT-BUG-24"));
    }

    @Test
    void missingBugIdReturnsSuccessAndSkips() {
        ZenTaoWebhookEventService eventService = mock(ZenTaoWebhookEventService.class);
        ZenTaoWebhookController controller = new ZenTaoWebhookController(
                eventService, new ObjectMapper(), "callback-secret");

        controller.handleWebhook(null, "callback-secret",
                "{\"objectType\":\"task\",\"objectID\":24,\"action\":\"edited\"}");

        verify(eventService, never()).record(any(JsonNode.class), any(), any());
    }

    @Test
    void malformedPayloadReturnsBadRequest() {
        ZenTaoWebhookEventService eventService = mock(ZenTaoWebhookEventService.class);
        ZenTaoWebhookController controller = new ZenTaoWebhookController(
                eventService, new ObjectMapper(), "callback-secret");

        assertThatThrownBy(() -> controller.handleWebhook(null, "callback-secret", "{bad json"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(eventService, never()).record(any(JsonNode.class), any(), any());
    }
}
