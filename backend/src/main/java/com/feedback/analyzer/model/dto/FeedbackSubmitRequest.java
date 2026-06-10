package com.feedback.analyzer.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.feedback.analyzer.util.FlexibleLocalDateTimeDeserializer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FeedbackSubmitRequest {

    @NotBlank(message = "Webhook Token 不能为空")
    @JsonAlias({"token", "webhook_token"})
    private String webhookToken;

    @NotBlank(message = "sourceKey 不能为空")
    @JsonAlias({"source_key", "entryKey"})
    private String sourceKey;

    @NotBlank(message = "channel 不能为空")
    @JsonAlias({"source", "sourceName"})
    private String channel;

    @NotBlank(message = "反馈内容不能为空")
    @Size(max = 2000, message = "反馈内容不能超过 2000 个字符")
    @JsonAlias({"content", "text", "feedback", "description"})
    private String rawContent;

    @JsonAlias({"externalUserId", "openId"})
    private String userId;

    @JsonAlias({"username", "nickname"})
    private String userName;

    @JsonAlias({"version"})
    private String appVersion;

    @JsonAlias({"device", "environment"})
    private String deviceInfo;

    @JsonAlias({"rating", "score"})
    @Min(value = 1, message = "满意度评分最小为 1")
    @Max(value = 5, message = "满意度评分最大为 5")
    private Integer star;

    @JsonAlias({"time", "createdAt", "submitTime"})
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime feedbackTime;
}
