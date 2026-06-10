package com.feedback.analyzer.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "weekly_report")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeeklyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate weekStart;

    @Column(nullable = false)
    private Long productId;

    private LocalDate weekEnd;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String rawData;

    @Column(nullable = false)
    private Boolean isSent;

    @Column
    private LocalDateTime sentAt;

    @Column(nullable = false)
    private LocalDateTime generatedAt;

    @PrePersist
    public void prePersist() {
        if (generatedAt == null) {
            generatedAt = LocalDateTime.now();
        }
        if (isSent == null) {
            isSent = false;
        }
    }
}
