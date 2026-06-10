package com.feedback.analyzer.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiFeedbackDecision {

    private String action;
    private String issueId;
    private String reason;
    private double confidence;
    private String category;
    private String module;
    private String summary;
    private List<String> keywords;
    private boolean notify;
    private boolean createZenTao;
}
