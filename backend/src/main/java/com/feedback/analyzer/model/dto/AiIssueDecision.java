package com.feedback.analyzer.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiIssueDecision {

    private String action;
    private String reason;
    private boolean notificationSent;
    private long recentCount;
    private String plan;
    private String zentaoAction;
    private String zentaoReason;
    private String zentaoIssueKey;
    private String notificationReason;
    private String notificationMessage;
}
