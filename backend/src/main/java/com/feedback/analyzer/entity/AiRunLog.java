package com.feedback.analyzer.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_run_log", indexes = {
    @Index(name = "idx_ai_type", columnList = "aiType"),
    @Index(name = "idx_ai_target", columnList = "targetType,targetId"),
    @Index(name = "idx_ai_status", columnList = "status"),
    @Index(name = "idx_ai_created", columnList = "createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiRunLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String aiType;

    @Column(nullable = false, length = 32)
    private String targetType;

    @Column(nullable = false, length = 64)
    private String targetId;

    @Column
    private Long productId;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String inputContext;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String thoughtTrace;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String toolCalls;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String decision;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = "RUNNING";
        }
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
