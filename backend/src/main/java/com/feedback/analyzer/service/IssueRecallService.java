package com.feedback.analyzer.service;

import com.feedback.analyzer.entity.FeedbackAnalyzed;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.repository.FeedbackIssueEsRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class IssueRecallService {

    private final FeedbackIssueRepository issueRepo;
    private final FeedbackIssueEsRepository issueEsRepo;

    public RecallResult search(FeedbackAnalyzed analyzed,
                               List<Double> embedding,
                               int limit,
                               double similarityThreshold) {
        return search(analyzed, embedding, limit, similarityThreshold, analyzed.getCategory());
    }

    public RecallResult search(FeedbackAnalyzed analyzed,
                               List<Double> embedding,
                               int limit,
                               double similarityThreshold,
                               FeedbackCategoryEnum category) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, Set<String>> sources = new LinkedHashMap<>();
        int knnMatchCount = 0;

        if (embedding != null && !embedding.isEmpty()) {
            double[] vector = embedding.stream().mapToDouble(Double::doubleValue).toArray();
            var matches = issueEsRepo.knnSearchWithScore(
                    vector, analyzed.getProductId(), category != null ? category.name() : null,
                    limit, (float) similarityThreshold);
            for (var match : matches) {
                FeedbackIssue issue = issueRepo.findById(match.document().getIssueId())
                        .filter(found -> canUseIssue(found, analyzed.getProductId(), category))
                        .orElse(null);
                if (issue != null) {
                    knnMatchCount++;
                    addCandidate(issue, candidates, seen, sources, "KNN", limit, match.score());
                }
            }
        }

        for (Map<String, Object> candidate : candidates) {
            Object issueId = candidate.get("issueId");
            if (issueId != null && sources.containsKey(issueId.toString())) {
                candidate.put("sources", new ArrayList<>(sources.get(issueId.toString())));
            }
        }

        List<String> candidateIssueIds = candidates.stream()
                .map(candidate -> String.valueOf(candidate.get("issueId")))
                .toList();
        return new RecallResult(candidates, candidateIssueIds, knnMatchCount);
    }

    private boolean canUseIssue(FeedbackIssue issue, Long productId, FeedbackCategoryEnum category) {
        return issue != null
                && issue.getProductId().equals(productId)
                && (category == null || issue.getCategory() == category)
                && issue.getStatus() != null
                && !issue.getStatus().isArchivedStatus();
    }

    private void addCandidate(FeedbackIssue issue,
                              List<Map<String, Object>> candidates,
                              Set<String> seen,
                              Map<String, Set<String>> sources,
                              String source,
                              int limit,
                              double score) {
        sources.computeIfAbsent(issue.getId(), ignored -> new LinkedHashSet<>()).add(source);
        if (seen.contains(issue.getId()) || candidates.size() >= limit) {
            return;
        }
        seen.add(issue.getId());
        Map<String, Object> candidate = issueSnapshot(issue);
        candidate.put("rank", candidates.size() + 1);
        candidate.put("score", score);
        candidates.add(candidate);
    }

    private Map<String, Object> issueSnapshot(FeedbackIssue issue) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("issueId", value(issue.getId()));
        map.put("title", value(issue.getTitle()));
        map.put("category", issue.getCategory() != null ? issue.getCategory().name() : "");
        map.put("module", value(issue.getModule()));
        map.put("severity", issue.getCategory() == FeedbackCategoryEnum.BUG ? value(issue.getSeverity()) : "");
        map.put("status", issue.getStatus() != null ? issue.getStatus().name() : "");
        map.put("reportCount", issue.getReportCount() != null ? issue.getReportCount() : 0);
        map.put("summary", value(issue.getAiSummary()));
        map.put("typicalContent", value(issue.getTypicalContent()));
        return map;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    public record RecallResult(
            List<Map<String, Object>> candidates,
            List<String> candidateIssueIds,
            int knnMatchCount
    ) {
    }
}
