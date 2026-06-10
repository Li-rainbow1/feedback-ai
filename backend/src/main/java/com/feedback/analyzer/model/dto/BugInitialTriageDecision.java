package com.feedback.analyzer.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BugInitialTriageDecision {

    private String severity;
    private String priority;
    private double confidence;
    private String reason;
    private String source;

    public static BugInitialTriageDecision systemDefault(String reason) {
        return BugInitialTriageDecision.builder()
                .severity("MEDIUM")
                .priority("P3")
                .confidence(0.0)
                .reason(reason)
                .source("SYSTEM_DEFAULT")
                .build();
    }
}
