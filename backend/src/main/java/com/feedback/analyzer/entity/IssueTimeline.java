package com.feedback.analyzer.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "issue_timeline", indexes = {
    @Index(name = "idx_timeline_issue", columnList = "issueId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IssueTimeline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String issueId;

    @Column(nullable = false, length = 32)
    private String eventType;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
