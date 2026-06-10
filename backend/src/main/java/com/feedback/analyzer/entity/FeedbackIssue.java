package com.feedback.analyzer.entity;

import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "feedback_issue", indexes = {
    @Index(name = "idx_issue_status", columnList = "status"),
    @Index(name = "idx_issue_severity", columnList = "severity"),
    @Index(name = "idx_issue_priority", columnList = "priority"),
    @Index(name = "idx_issue_module", columnList = "module"),
    @Index(name = "idx_issue_category", columnList = "category"),
    @Index(name = "idx_issue_product", columnList = "productId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedbackIssue {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 256)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FeedbackCategoryEnum category;

    @Column(nullable = false)
    private Long productId;

    @Column(length = 64)
    private String module;

    @Column(nullable = false, length = 12)
    private String severity;

    @Column(length = 2)
    private String priority;

    @Column(length = 32)
    private String triageSource;

    @Column(columnDefinition = "TEXT")
    private String triageReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private IssueStatusEnum status;

    @Column
    private Boolean confirmed;

    @Column(nullable = false)
    private Integer reportCount;

    @Column(length = 128)
    private String affectVersions;

    @Column(nullable = false)
    private LocalDateTime firstReportAt;

    @Column(nullable = false)
    private LocalDateTime latestReportAt;

    @Column(columnDefinition = "TEXT")
    private String aiSummary;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String embeddingVector;

    @Column(length = 128)
    private String relatedIssue;

    @Column(columnDefinition = "TEXT")
    private String suspectedDuplicates;

    @Column(columnDefinition = "TEXT")
    private String typicalContent;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime resolvedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (reportCount == null) {
            reportCount = 0;
        }
        if (priority == null || priority.isBlank()) {
            priority = "P3";
        }
        if (triageSource == null || triageSource.isBlank()) {
            triageSource = "SYSTEM_DEFAULT";
        }
        if (confirmed == null) {
            confirmed = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
