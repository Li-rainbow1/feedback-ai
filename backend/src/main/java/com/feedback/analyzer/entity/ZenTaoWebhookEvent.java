package com.feedback.analyzer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "zentao_webhook_event", indexes = {
        @Index(name = "idx_zentao_event_status_retry", columnList = "status,next_retry_at"),
        @Index(name = "idx_zentao_event_issue", columnList = "issue_key"),
        @Index(name = "idx_zentao_event_received", columnList = "received_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZenTaoWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_key", nullable = false, length = 64)
    private String issueKey;

    @Column(name = "object_type", length = 32)
    private String objectType;

    @Column(name = "object_id", length = 64)
    private String objectId;

    @Column(length = 64)
    private String action;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (status == null || status.isBlank()) {
            status = "PENDING";
        }
        if (retryCount == null) {
            retryCount = 0;
        }
        if (receivedAt == null) {
            receivedAt = now;
        }
        if (nextRetryAt == null) {
            nextRetryAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
