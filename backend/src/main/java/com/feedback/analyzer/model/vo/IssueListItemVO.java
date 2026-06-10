package com.feedback.analyzer.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class IssueListItemVO {

    private String id;
    private String title;
    private String category;
    private String categoryLabel;
    private String module;
    private String severity;
    private String priority;
    private String status;
    private String statusLabel;
    private Boolean confirmed;
    private String confirmedLabel;
    private Integer reportCount;
    private LocalDateTime firstReportAt;
    private LocalDateTime latestReportAt;
    private String relatedIssue;
}
