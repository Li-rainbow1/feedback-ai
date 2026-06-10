package com.feedback.analyzer.util;

public class TextCleanUtil {

    private static final int MIN_CONTENT_LENGTH = 2;
    private static final int MAX_CONTENT_LENGTH = 5000;

    public static boolean isLowQuality(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        String trimmed = clean(content);
        if (trimmed.length() < MIN_CONTENT_LENGTH) {
            return true;
        }
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            return false;
        }
        String repeated = trimmed.replaceAll("(.)\\1{10,}", "");
        return repeated.length() < MIN_CONTENT_LENGTH;
    }

    public static String clean(String content) {
        if (content == null) {
            return "";
        }
        return content
                .replaceAll("[\\p{So}\\p{Cn}]", "")
                .replaceAll("\\s+", " ")
                .replace("【】", "")
                .trim();
    }

    public static String normalize(String content) {
        return clean(content).toLowerCase();
    }
}
