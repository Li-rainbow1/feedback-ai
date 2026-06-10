package com.feedback.analyzer.model.vo;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class IssueVO {

    private String id;
    private String title;
    private String category;
    private String module;
    private String severity;
    private String priority;
    private String triageSource;
    private String triageSourceLabel;
    private String triageReason;
    private String triageReasonDisplay;
    private String status;
    private Boolean confirmed;
    private String confirmedLabel;
    private int reportCount;
    private String affectVersions;
    private LocalDateTime firstReportAt;
    private LocalDateTime latestReportAt;
    private String aiSummary;
    private String relatedIssue;
    private List<Map<String, Object>> suspectedDuplicates;
    private List<Map<String, Object>> mergeEvidence;
    private String typicalContent;
    private List<Map<String, Object>> timeline;
    private List<Map<String, Object>> sampleFeedbacks;
    private LocalDateTime resolvedAt;
}
