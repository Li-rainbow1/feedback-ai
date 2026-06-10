package com.feedback.analyzer.model.dto;

import lombok.Data;

@Data
public class IssueTriageUpdateRequest {

    private String severity;
    private String priority;
    private String reason;
}
