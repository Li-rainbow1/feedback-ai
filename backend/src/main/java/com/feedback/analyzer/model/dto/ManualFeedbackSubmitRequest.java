package com.feedback.analyzer.model.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.feedback.analyzer.util.FlexibleLocalDateTimeDeserializer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ManualFeedbackSubmitRequest {

    @NotNull(message = "产品不能为空")
    private Long productId;

    @NotBlank(message = "渠道不能为空")
    private String channel;

    @NotBlank(message = "反馈内容不能为空")
    @Size(max = 2000, message = "反馈内容不能超过 2000 个字符")
    private String rawContent;

    private String userId;

    private String userName;

    private String appVersion;

    private String deviceInfo;

    @Min(value = 1, message = "满意度评分最小为 1")
    @Max(value = 5, message = "满意度评分最大为 5")
    private Integer star;

    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime feedbackTime;

    public FeedbackSubmitRequest toSubmitRequest() {
        FeedbackSubmitRequest request = new FeedbackSubmitRequest();
        request.setChannel(channel);
        request.setRawContent(rawContent);
        request.setUserId(userId);
        request.setUserName(userName);
        request.setAppVersion(appVersion);
        request.setDeviceInfo(deviceInfo);
        request.setStar(star);
        request.setFeedbackTime(feedbackTime);
        return request;
    }
}
