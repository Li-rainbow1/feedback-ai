package com.feedback.analyzer.model.enums;

public enum FeedbackCategoryEnum {
    BUG("Bug"),
    SUGGESTION("建议"),
    PRAISE("好评");

    private final String label;

    FeedbackCategoryEnum(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
