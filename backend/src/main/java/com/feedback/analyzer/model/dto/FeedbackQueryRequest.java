package com.feedback.analyzer.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class FeedbackQueryRequest {

    @NotNull
    private Long productId;
    private String category;
    private String module;
    private String keyword;
    private LocalDateTime start;
    private LocalDateTime end;
    private Integer page = 1;
    private Integer size = 20;
}
