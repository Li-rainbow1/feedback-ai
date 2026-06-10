package com.feedback.analyzer.model.vo;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class DashboardVO {

    private long todayCount;
    private long weekCount;
    private long totalCount;
    private long openIssueCount;
    private LocalDateTime rangeStart;
    private LocalDateTime rangeEnd;

    private List<Map<String, Object>> categoryBreakdown;
    private List<Map<String, Object>> moduleDistribution;
    private List<Map<String, Object>> topIssues;
    private List<Map<String, Object>> channelDistribution;
    private List<Map<String, Object>> dailyTrend;
    private Map<String, Object> bugBoard;
    private Map<String, Object> suggestionBoard;
    private Map<String, Object> praiseBoard;
}
