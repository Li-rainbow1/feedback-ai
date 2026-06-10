package com.feedback.analyzer.model.enums;

public enum FeedbackStatusEnum {
    RAW("raw", "刚接收"),
    CLEANED("cleaned", "已清洗"),
    SKIPPED("skipped", "已跳过"),
    ANALYZING("analyzing", "分析中"),
    ANALYZED("analyzed", "已分析"),
    LOW_QUALITY("low_quality", "低质量");

    private final String code;
    private final String label;

    FeedbackStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}
