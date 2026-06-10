package com.feedback.analyzer.entity;

import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "feedback_raw", uniqueConstraints = {
    @UniqueConstraint(name = "uk_raw_channel_external", columnNames = {"channel_id", "external_review_id"}),
    @UniqueConstraint(name = "uk_raw_public_source_external", columnNames = {"public_review_source_id", "external_review_id"})
}, indexes = {
    @Index(name = "idx_raw_channel", columnList = "channel"),
    @Index(name = "idx_raw_channel_id", columnList = "channel_id"),
    @Index(name = "idx_raw_channel_external", columnList = "channel_id, external_review_id"),
    @Index(name = "idx_raw_source_type", columnList = "source_type"),
    @Index(name = "idx_raw_public_source", columnList = "public_review_source_id"),
    @Index(name = "idx_raw_collection_run", columnList = "collection_run_id"),
    @Index(name = "idx_raw_status", columnList = "status"),
    @Index(name = "idx_raw_processing_started", columnList = "processing_started_at"),
    @Index(name = "idx_raw_time", columnList = "feedbackTime"),
    @Index(name = "idx_raw_user", columnList = "userId"),
    @Index(name = "idx_raw_product", columnList = "productId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedbackRaw {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 32)
    private String channel;

    @Column(name = "channel_id")
    private Long channelId;

    @Column(name = "source_type", length = 32)
    private String sourceType;

    @Column(name = "external_review_id", length = 128)
    private String externalReviewId;

    @Column(name = "public_review_source_id")
    private Long publicReviewSourceId;

    @Column(name = "collection_run_id")
    private Long collectionRunId;

    @Column(name = "source_metadata", columnDefinition = "TEXT")
    private String sourceMetadata;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String rawContent;

    @Column(length = 64)
    private String userId;

    private Integer star;

    @Column(length = 128)
    private String userName;

    @Column(length = 32)
    private String appVersion;

    @Column(nullable = false)
    private Long productId;

    @Column(length = 256)
    private String deviceInfo;

    @Column(nullable = false)
    private LocalDateTime feedbackTime;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private FeedbackStatusEnum status;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "processing_error", columnDefinition = "TEXT")
    private String processingError;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = FeedbackStatusEnum.RAW;
        }
    }
}
