package com.feedback.analyzer.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "channel_config", indexes = {
    @Index(name = "idx_channel_product", columnList = "productId"),
    @Index(name = "idx_channel_type", columnList = "type"),
    @Index(name = "idx_channel_source_key", columnList = "sourceKey")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(length = 32)
    private String sourceType;

    @Column(length = 128)
    private String sourceKey;

    @Column(columnDefinition = "TEXT")
    private String credentials;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false)
    private LocalDateTime createdAt;

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
        if (enabled == null) {
            enabled = true;
        }
        normalizeType();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
        normalizeType();
    }

    private void normalizeType() {
        if ("webhook".equals(type)) {
            type = "push";
        }
        if (type == null || type.isBlank()) {
            type = "push";
        }
        if (sourceType == null || sourceType.isBlank()) {
            sourceType = "webhook";
        }
    }
}
