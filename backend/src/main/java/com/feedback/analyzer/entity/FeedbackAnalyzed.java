package com.feedback.analyzer.entity;

import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "feedback_analyzed", indexes = {
    @Index(name = "idx_analyzed_issue", columnList = "issueId"),
    @Index(name = "idx_analyzed_category", columnList = "category"),
    @Index(name = "idx_analyzed_time", columnList = "analyzedAt"),
    @Index(name = "idx_analyzed_product", columnList = "productId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedbackAnalyzed {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String rawId;

    @Column(nullable = false)
    private Long productId;

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

    @Column(columnDefinition = "MEDIUMTEXT")
    private String embeddingVector;

    @Column(nullable = false)
    private LocalDateTime analyzedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private FeedbackStatusEnum status;

    @PrePersist
    public void prePersist() {
        if (analyzedAt == null) {
            analyzedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = FeedbackStatusEnum.ANALYZED;
        }
    }
}
