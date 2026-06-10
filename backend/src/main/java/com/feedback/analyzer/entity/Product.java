package com.feedback.analyzer.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "product")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(length = 256)
    private String description;

    @Column(length = 128)
    private String teamName;

    @Column(nullable = false, unique = true, length = 64)
    private String webhookToken;

    private Integer zentaoProductId;

    @Column
    private Boolean feishuEnabled;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 512)
    private String feishuWebhookUrl;

    @Transient
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Boolean clearFeishuWebhook;

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
        if (feishuEnabled == null) {
            feishuEnabled = false;
        }
        if (webhookToken == null) {
            webhookToken = java.util.UUID.randomUUID().toString().replace("-", "");
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Transient
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public boolean isFeishuConfigured() {
        return feishuWebhookUrl != null && !feishuWebhookUrl.isBlank();
    }

    @Transient
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getFeishuStatus() {
        if (!Boolean.TRUE.equals(feishuEnabled)) {
            return "DISABLED";
        }
        return isFeishuConfigured() ? "ENABLED" : "MISSING_WEBHOOK";
    }

    @Transient
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getFeishuStatusLabel() {
        return switch (getFeishuStatus()) {
            case "ENABLED" -> "已启用";
            case "MISSING_WEBHOOK" -> "未配置地址";
            default -> "已关闭";
        };
    }
}
