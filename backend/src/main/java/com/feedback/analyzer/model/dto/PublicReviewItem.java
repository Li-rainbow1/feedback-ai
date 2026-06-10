package com.feedback.analyzer.model.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record PublicReviewItem(
        String externalId,
        String content,
        String userId,
        String userName,
        Integer star,
        String appVersion,
        String deviceInfo,
        LocalDateTime feedbackTime,
        Map<String, Object> metadata
) {
}
