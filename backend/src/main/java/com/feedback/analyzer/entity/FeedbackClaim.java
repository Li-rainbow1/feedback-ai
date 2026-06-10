package com.feedback.analyzer.entity;

import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.FeedbackClaimStatusEnum;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "feedback_claim", indexes = {
        @Index(name = "idx_claim_raw", columnList = "rawId"),
        @Index(name = "idx_claim_analyzed", columnList = "analyzedId"),
        @Index(name = "idx_claim_issue", columnList = "issueId"),
        @Index(name = "idx_claim_product", columnList = "productId"),
        @Index(name = "idx_claim_category", columnList = "category"),
        @Index(name = "idx_claim_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedbackClaim {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String rawId;

    @Column(nullable = false, length = 64)
    private String analyzedId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Integer claimIndex;

    @Column(nullable = false)
    private Boolean primaryClaim;

    @Column(length = 64)
    private String issueId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FeedbackCategoryEnum category;

    @Column(length = 64)
    private String module;

    @Column(length = 256)
    private String keywords;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String embeddingVector;

    @Column(length = 256)
    private String praiseTarget;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String praiseTargetEmbeddingVector;

    @Column(length = 32)
    private String decisionAction;

    @Column(columnDefinition = "TEXT")
    private String decisionReason;

    @Column
    private Double decisionConfidence;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private FeedbackClaimStatusEnum status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (primaryClaim == null) {
            primaryClaim = false;
        }
        if (status == null) {
            status = FeedbackClaimStatusEnum.PENDING;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
