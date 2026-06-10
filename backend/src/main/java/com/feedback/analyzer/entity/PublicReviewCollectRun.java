package com.feedback.analyzer.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "public_review_collect_run", indexes = {
        @Index(name = "idx_collect_run_source", columnList = "source_id, started_at"),
        @Index(name = "idx_collect_run_product", columnList = "product_id"),
        @Index(name = "idx_collect_run_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicReviewCollectRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "run_type", nullable = false, length = 16)
    private String runType;

    @Column(nullable = false, length = 24)
    private String status;

    private Integer fetchedCount;

    private Integer newCount;

    private Integer duplicateCount;

    private Integer processedCount;

    private Integer affectedIssueCount;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @PrePersist
    public void prePersist() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "RUNNING";
        }
        if (fetchedCount == null) fetchedCount = 0;
        if (newCount == null) newCount = 0;
        if (duplicateCount == null) duplicateCount = 0;
        if (processedCount == null) processedCount = 0;
        if (affectedIssueCount == null) affectedIssueCount = 0;
    }
}
