package com.feedback.analyzer.util;

import com.feedback.analyzer.entity.FeedbackClaim;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class PraiseAggregationHelper {

    private PraiseAggregationHelper() {
    }

    public static List<Map<String, Object>> buildGroups(List<FeedbackClaim> claims) {
        Map<String, List<FeedbackClaim>> grouped = claims.stream()
                .collect(Collectors.groupingBy(claim -> normalizeModule(claim.getModule()),
                        LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .map(entry -> {
                    List<FeedbackClaim> items = entry.getValue();
                    FeedbackClaim representative = chooseRepresentative(items);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("module", entry.getKey());
                    item.put("count", items.size());
                    item.put("representativeSummary", representativeText(representative));
                    item.put("representativeContent", representative != null ? representative.getContent() : "");
                    item.put("keywords", topKeywords(items));
                    item.put("latestAt", items.stream()
                            .map(PraiseAggregationHelper::claimTime)
                            .filter(java.util.Objects::nonNull)
                            .max(LocalDateTime::compareTo)
                            .orElse(null));
                    return item;
                })
                .sorted((left, right) -> {
                    int byCount = Integer.compare(
                            ((Number) right.get("count")).intValue(),
                            ((Number) left.get("count")).intValue());
                    if (byCount != 0) {
                        return byCount;
                    }
                    LocalDateTime rightTime = (LocalDateTime) right.get("latestAt");
                    LocalDateTime leftTime = (LocalDateTime) left.get("latestAt");
                    if (rightTime == null || leftTime == null) {
                        return rightTime == null ? 1 : -1;
                    }
                    return rightTime.compareTo(leftTime);
                })
                .toList();
    }

    public static String normalizeModule(String module) {
        return module == null || module.isBlank() ? "未分类" : module.trim();
    }

    private static FeedbackClaim chooseRepresentative(List<FeedbackClaim> claims) {
        return claims.stream()
                .filter(claim -> {
                    String summary = claim.getSummary();
                    if (summary != null && summary.trim().length() >= 8) {
                        return true;
                    }
                    String content = claim.getContent();
                    return content != null && content.trim().length() >= 8;
                })
                .findFirst()
                .orElse(claims.isEmpty() ? null : claims.get(0));
    }

    private static String representativeText(FeedbackClaim claim) {
        if (claim == null) {
            return "";
        }
        String summary = safeText(claim.getSummary());
        if (!summary.isBlank()) {
            return summary;
        }
        return safeText(claim.getContent());
    }

    private static LocalDateTime claimTime(FeedbackClaim claim) {
        if (claim == null) {
            return null;
        }
        return claim.getCreatedAt() != null ? claim.getCreatedAt() : claim.getUpdatedAt();
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String topKeywords(List<FeedbackClaim> claims) {
        Map<String, Integer> counts = new HashMap<>();
        for (FeedbackClaim claim : claims) {
            if (claim.getKeywords() == null || claim.getKeywords().isBlank()) {
                continue;
            }
            for (String token : claim.getKeywords().split("[,，\\s]+")) {
                String keyword = token.trim();
                if (!keyword.isBlank()) {
                    counts.merge(keyword, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .sorted((left, right) -> {
                    int byCount = Integer.compare(right.getValue(), left.getValue());
                    return byCount != 0 ? byCount : left.getKey().compareTo(right.getKey());
                })
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(","));
    }
}
