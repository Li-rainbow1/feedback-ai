package com.feedback.analyzer.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.AiRunLog;
import com.feedback.analyzer.repository.AiRunLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AiRunLogger {

    private final AiRunLogRepository logRepo;
    private final ObjectMapper objectMapper;

    public AiRunLog start(String aiType, String targetType, String targetId,
                             Long productId, Object inputContext) {
        return logRepo.save(AiRunLog.builder()
                .aiType(aiType)
                .targetType(targetType)
                .targetId(targetId)
                .productId(productId)
                .inputContext(toJson(inputContext))
                .status("RUNNING")
                .startedAt(LocalDateTime.now())
                .build());
    }

    public void success(AiRunLog log, List<String> thoughts, List<Map<String, Object>> toolCalls, Object decision) {
        log.setThoughtTrace(toJson(thoughts));
        log.setToolCalls(toJson(toolCalls));
        log.setDecision(toJson(decision));
        log.setStatus("SUCCESS");
        log.setFinishedAt(LocalDateTime.now());
        logRepo.save(log);
    }

    public void failed(AiRunLog log, List<String> thoughts, List<Map<String, Object>> toolCalls, Exception e) {
        log.setThoughtTrace(toJson(thoughts));
        log.setToolCalls(toJson(toolCalls));
        log.setStatus("FAILED");
        log.setErrorMessage(e.getMessage());
        log.setFinishedAt(LocalDateTime.now());
        logRepo.save(log);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
