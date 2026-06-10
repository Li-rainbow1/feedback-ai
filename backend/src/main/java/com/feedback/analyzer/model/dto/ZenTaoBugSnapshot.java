package com.feedback.analyzer.model.dto;

public record ZenTaoBugSnapshot(
        String issueKey,
        String status,
        Boolean confirmed,
        String severity,
        String priority
) {
}
