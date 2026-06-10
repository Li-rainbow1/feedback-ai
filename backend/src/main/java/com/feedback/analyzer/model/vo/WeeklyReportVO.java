package com.feedback.analyzer.model.vo;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class WeeklyReportVO {

    private Long id;
    private String productName;
    private LocalDate weekStart;
    private LocalDate weekEnd;
    private String content;
    private Boolean isSent;
    private LocalDateTime sentAt;
    private LocalDateTime generatedAt;
}
