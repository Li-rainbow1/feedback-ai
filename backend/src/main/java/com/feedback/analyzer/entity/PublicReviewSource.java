package com.feedback.analyzer.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "public_review_source", uniqueConstraints = {
        @UniqueConstraint(name = "uk_public_review_product_platform_app",
                columnNames = {"product_id", "platform", "app_id"})
}, indexes = {
        @Index(name = "idx_public_review_product", columnList = "product_id"),
        @Index(name = "idx_public_review_enabled", columnList = "enabled, scheduled_enabled")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicReviewSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 20)
    private String platform;

    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    @Column(length = 12)
    private String region;

    @Column(length = 24)
    private String language;

    @Column(name = "initialization_limit", nullable = false)
    private Integer initializationLimit;

    @Column(nullable = false)
    private Boolean initialized;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(name = "scheduled_enabled", nullable = false)
    private Boolean scheduledEnabled;

    private LocalDateTime lastCollectedAt;

    private LocalDateTime lastSuccessAt;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    private Integer lastNewCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Transient
    private Boolean busy;

    @Transient
    private String activeRunStatus;

    @Transient
    private Boolean hasReviewData;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (initializationLimit == null) {
            initializationLimit = 100;
        }
        if (initialized == null) {
            initialized = false;
        }
        if (enabled == null) {
            enabled = true;
        }
        if (scheduledEnabled == null) {
            scheduledEnabled = true;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
