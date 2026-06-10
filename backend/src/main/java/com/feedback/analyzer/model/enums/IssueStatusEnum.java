package com.feedback.analyzer.model.enums;

public enum IssueStatusEnum {
    OPEN("open", "待处理"),
    /**
     * 兼容旧数据读取。Bug 的“已确认”已拆到 FeedbackIssue.confirmed。
     */
    ACKNOWLEDGED("acknowledged", "已确认"),
    FIXING("fixing", "修复中"),
    RESOLVED("resolved", "已解决"),
    MERGED("merged", "已归并"),

    EVALUATING("evaluating", "待评估"),
    ACCEPTED("accepted", "已采纳"),
    PLANNED("planned", "规划中"),
    IMPLEMENTED("implemented", "已实现"),
    NOT_ACCEPTED("not_accepted", "暂不采纳"),

    CLOSED("closed", "已关闭");

    private final String code;
    private final String label;

    IssueStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static IssueStatusEnum parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        for (IssueStatusEnum status : values()) {
            if (status.name().equalsIgnoreCase(normalized) || status.code.equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unsupported issue status: " + value);
    }

    public boolean isBugWorkflowStatus() {
        return this == OPEN || this == FIXING || this == RESOLVED || this == CLOSED;
    }

    public boolean isSuggestionWorkflowStatus() {
        return this == EVALUATING || this == ACCEPTED || this == PLANNED
                || this == IMPLEMENTED || this == NOT_ACCEPTED;
    }

    public boolean isArchivedStatus() {
        return this == MERGED || this == CLOSED;
    }
}
