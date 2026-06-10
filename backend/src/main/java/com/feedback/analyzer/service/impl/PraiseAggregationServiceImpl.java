package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackClaim;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.FeedbackClaimStatusEnum;
import com.feedback.analyzer.repository.FeedbackClaimRepository;
import com.feedback.analyzer.service.AiAnalysisService;
import com.feedback.analyzer.service.PraiseAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PraiseAggregationServiceImpl implements PraiseAggregationService {

    private final FeedbackClaimRepository claimRepo;
    private final AiAnalysisService aiService;
    private final ObjectMapper objectMapper;

    @Value("${praise.semantic-threshold:0.80}")
    private double similarityThreshold;

    @Override
    @Transactional
    public List<Map<String, Object>> buildGroups(Long productId,
                                                 LocalDateTime start,
                                                 LocalDateTime end,
                                                 int limit) {
        List<FeedbackClaim> claims = loadPraiseClaims(productId, start, end);
        List<Map<String, Object>> groups = buildSemanticGroups(claims);
        if (limit <= 0 || groups.size() <= limit) {
            return groups;
        }
        return groups.subList(0, limit);
    }

    @Override
    @Transactional
    public List<FeedbackClaim> getGroupClaims(Long productId,
                                              LocalDateTime start,
                                              LocalDateTime end,
                                              String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return List.of();
        }
        List<FeedbackClaim> claims = loadPraiseClaims(productId, start, end);
        List<Cluster> clusters = clusterClaims(claims);
        return clusters.stream()
                .filter(cluster -> groupId.equals(cluster.groupId()))
                .findFirst()
                .map(cluster -> cluster.members().stream()
                        .map(ClusterMember::claim)
                        .sorted(Comparator.comparing(this::claimTime, Comparator.nullsLast(LocalDateTime::compareTo)).reversed())
                        .toList())
                .orElse(List.of());
    }

    private List<FeedbackClaim> loadPraiseClaims(Long productId, LocalDateTime start, LocalDateTime end) {
        return claimRepo.findByProductIdAndCategoryAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                productId,
                FeedbackCategoryEnum.PRAISE,
                FeedbackClaimStatusEnum.RECORDED,
                start,
                end);
    }

    private List<Map<String, Object>> buildSemanticGroups(List<FeedbackClaim> claims) {
        return clusterClaims(claims).stream()
                .map(this::toGroup)
                .sorted(groupComparator())
                .toList();
    }

    private List<Cluster> clusterClaims(List<FeedbackClaim> claims) {
        if (claims.isEmpty()) {
            return List.of();
        }
        List<ClusterMember> members = prepareMembers(claims);
        int size = members.size();
        List<List<Integer>> adjacency = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            adjacency.add(new ArrayList<>());
        }
        for (int i = 0; i < size; i++) {
            ClusterMember leftMember = members.get(i);
            double[] left = leftMember.targetVector();
            for (int j = i + 1; j < size; j++) {
                ClusterMember rightMember = members.get(j);
                double[] right = rightMember.targetVector();
                if (sameTargetLabel(leftMember, rightMember)
                        || (left != null && right != null && cosineSimilarity(left, right) >= similarityThreshold)) {
                    adjacency.get(i).add(j);
                    adjacency.get(j).add(i);
                }
            }
        }
        boolean[] visited = new boolean[size];
        List<Cluster> clusters = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (visited[i]) {
                continue;
            }
            Cluster cluster = new Cluster();
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(i);
            visited[i] = true;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                cluster.add(members.get(current));
                for (Integer next : adjacency.get(current)) {
                    if (!visited[next]) {
                        visited[next] = true;
                        queue.addLast(next);
                    }
                }
            }
            clusters.add(cluster);
        }
        clusters.forEach(Cluster::finalizeGroup);
        return clusters;
    }

    private List<ClusterMember> prepareMembers(List<FeedbackClaim> claims) {
        Map<String, FeedbackClaim> dirtyClaims = new LinkedHashMap<>();
        Map<String, double[]> targetVectorCache = new HashMap<>();
        List<ClusterMember> members = new ArrayList<>();
        for (FeedbackClaim claim : claims) {
            double[] claimVector = parseVector(claim.getEmbeddingVector());
            if (claimVector == null) {
                claimVector = generateAndPersistVector(claim, dirtyClaims);
            }
            TargetSpec targetSpec = targetSpec(claim);
            double[] targetVector = targetVectorForClaim(claim, targetSpec, targetVectorCache, dirtyClaims);
            members.add(new ClusterMember(
                    claim,
                    normalize(targetVector != null ? targetVector : claimVector),
                    targetSpec.label(),
                    normalizeTargetLabel(targetSpec.label())));
        }
        if (!dirtyClaims.isEmpty()) {
            claimRepo.saveAll(new ArrayList<>(dirtyClaims.values()));
        }
        return members;
    }

    private double[] generateAndPersistVector(FeedbackClaim claim, Map<String, FeedbackClaim> dirtyClaims) {
        String text = buildEmbeddingText(claim);
        if (text.isBlank()) {
            return null;
        }
        List<Double> embedding = aiService.generateEmbedding(text);
        if (embedding == null || embedding.isEmpty()) {
            log.warn("Praise claim {} embedding generation failed", claim.getId());
            return null;
        }
        try {
            claim.setEmbeddingVector(objectMapper.writeValueAsString(embedding));
            markDirty(claim, dirtyClaims);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize praise embedding for claim {}", claim.getId(), e);
        }
        return normalize(embedding.stream().mapToDouble(Double::doubleValue).toArray());
    }

    private String buildEmbeddingText(FeedbackClaim claim) {
        return String.join(" ",
                blankToEmpty(claim.getCategory() != null ? claim.getCategory().name() : null),
                blankToEmpty(claim.getModule()),
                blankToEmpty(claim.getSummary()),
                blankToEmpty(claim.getKeywords()).replace(",", " "),
                blankToEmpty(claim.getContent()))
                .trim();
    }

    private double[] targetVectorForClaim(FeedbackClaim claim,
                                          TargetSpec targetSpec,
                                          Map<String, double[]> targetVectorCache,
                                          Map<String, FeedbackClaim> dirtyClaims) {
        if (targetSpec.label().isBlank() || targetSpec.embeddingText().isBlank()) {
            return null;
        }
        String oldTarget = blankToEmpty(claim.getPraiseTarget());
        if (!Objects.equals(oldTarget, targetSpec.label())) {
            claim.setPraiseTarget(targetSpec.label());
            claim.setPraiseTargetEmbeddingVector(null);
            markDirty(claim, dirtyClaims);
        }

        double[] storedVector = parseVector(claim.getPraiseTargetEmbeddingVector());
        if (storedVector != null) {
            return storedVector;
        }

        double[] generatedVector;
        if (targetVectorCache.containsKey(targetSpec.embeddingText())) {
            generatedVector = targetVectorCache.get(targetSpec.embeddingText());
        } else {
            generatedVector = generateTargetVector(targetSpec.embeddingText());
            targetVectorCache.put(targetSpec.embeddingText(), generatedVector);
        }
        if (generatedVector != null) {
            claim.setPraiseTargetEmbeddingVector(serializeVector(generatedVector));
            markDirty(claim, dirtyClaims);
        }
        return generatedVector;
    }

    private TargetSpec targetSpec(FeedbackClaim claim) {
        String label = targetLabel(claim);
        if (label.isBlank()) {
            return new TargetSpec("", "");
        }
        return new TargetSpec(label, "评价对象 " + label);
    }

    private double[] generateTargetVector(String text) {
        try {
            List<Double> embedding = aiService.generateEmbedding(text);
            if (embedding == null || embedding.isEmpty()) {
                log.warn("Praise target embedding generation failed");
                return null;
            }
            return normalize(embedding.stream().mapToDouble(Double::doubleValue).toArray());
        } catch (Exception e) {
            log.warn("Praise target embedding generation failed: {}", e.getMessage());
            return null;
        }
    }

    private double[] parseVector(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            List<Double> embedding = objectMapper.readValue(value, new TypeReference<List<Double>>() {});
            if (embedding == null || embedding.isEmpty()) {
                return null;
            }
            return normalize(embedding.stream().mapToDouble(Double::doubleValue).toArray());
        } catch (Exception e) {
            log.warn("Failed to parse praise embedding vector", e);
            return null;
        }
    }

    private String serializeVector(double[] vector) {
        try {
            return objectMapper.writeValueAsString(Arrays.stream(vector).boxed().toList());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize praise target embedding vector", e);
            return null;
        }
    }

    private void markDirty(FeedbackClaim claim, Map<String, FeedbackClaim> dirtyClaims) {
        if (claim != null && claim.getId() != null) {
            dirtyClaims.put(claim.getId(), claim);
        }
    }

    private Map<String, Object> toGroup(Cluster cluster) {
        FeedbackClaim representative = cluster.representative();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("groupId", cluster.groupId());
        item.put("module", defaultIfBlank(cluster.displayModule(), "未分类对象"));
        item.put("count", cluster.rawIds().size());
        item.put("representativeSummary", representativeText(representative));
        item.put("representativeContent", representative != null ? defaultIfBlank(representative.getContent(), "") : "");
        item.put("keywords", topKeywords(cluster.members()));
        item.put("latestAt", cluster.latestAt());
        return item;
    }

    private Comparator<Map<String, Object>> groupComparator() {
        return Comparator
                .comparingLong((Map<String, Object> item) -> -toLong(item.get("count")))
                .thenComparing(item -> (LocalDateTime) item.get("latestAt"), Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private String topKeywords(List<ClusterMember> members) {
        Map<String, Integer> counts = new HashMap<>();
        for (ClusterMember member : members) {
            String keywords = member.claim().getKeywords();
            if (keywords == null || keywords.isBlank()) {
                continue;
            }
            for (String token : keywords.split("[,，、\\s]+")) {
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

    private LocalDateTime claimTime(FeedbackClaim claim) {
        if (claim == null) {
            return null;
        }
        if (claim.getCreatedAt() != null) {
            return claim.getCreatedAt();
        }
        return claim.getUpdatedAt();
    }

    private String representativeText(FeedbackClaim claim) {
        if (claim == null) {
            return "";
        }
        String summary = defaultIfBlank(claim.getSummary(), "");
        if (!summary.isBlank()) {
            return summary;
        }
        return defaultIfBlank(claim.getContent(), "");
    }

    private double[] normalize(double[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        double norm = 0d;
        for (double value : vector) {
            norm += value * value;
        }
        if (norm <= 0d) {
            return null;
        }
        double scale = Math.sqrt(norm);
        double[] normalized = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / scale;
        }
        return normalized;
    }

    private double cosineSimilarity(double[] left, double[] right) {
        if (left == null || right == null || left.length != right.length) {
            return Double.NEGATIVE_INFINITY;
        }
        double dot = 0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        return dot;
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String targetLabel(FeedbackClaim claim) {
        String module = blankToEmpty(claim.getModule());
        if (!module.isBlank()) {
            return module;
        }
        String cachedTarget = blankToEmpty(claim.getPraiseTarget());
        if (!cachedTarget.isBlank()) {
            return cachedTarget;
        }
        return firstKeyword(claim.getKeywords());
    }

    private String firstKeyword(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return "";
        }
        for (String token : keywords.split("[,，、\\s]+")) {
            String keyword = token.trim();
            if (!keyword.isBlank()) {
                return keyword;
            }
        }
        return "";
    }

    private boolean sameTargetLabel(ClusterMember left, ClusterMember right) {
        return !left.normalizedTargetLabel().isBlank()
                && left.normalizedTargetLabel().equals(right.normalizedTargetLabel());
    }

    private String normalizeTargetLabel(String label) {
        return blankToEmpty(label)
                .replaceAll("[\\s　]+", "");
    }

    private final class Cluster {
        private final List<ClusterMember> members = new ArrayList<>();
        private final List<double[]> vectors = new ArrayList<>();
        private double[] center;
        private FeedbackClaim representative;
        private LocalDateTime latestAt;
        private String groupId;
        private String displayModule;
        private final java.util.Set<String> rawIds = new java.util.LinkedHashSet<>();

        private void add(ClusterMember member) {
            members.add(member);
            rawIds.add(member.claim().getRawId());
            if (member.targetVector() != null) {
                vectors.add(member.targetVector());
                center = recomputeCenter();
            }
            LocalDateTime memberTime = claimTime(member.claim());
            if (latestAt == null || (memberTime != null && memberTime.isAfter(latestAt))) {
                latestAt = memberTime;
            }
        }

        private void finalizeGroup() {
            representative = chooseRepresentative();
            groupId = representative != null ? representative.getId() : members.get(0).claim().getId();
            displayModule = chooseDisplayModule();
        }

        private FeedbackClaim chooseRepresentative() {
            if (members.isEmpty()) {
                return null;
            }
            if (center == null) {
                return members.stream()
                        .map(ClusterMember::claim)
                        .sorted(Comparator.comparing(PraiseAggregationServiceImpl.this::claimTime,
                                Comparator.nullsLast(LocalDateTime::compareTo)).reversed())
                        .findFirst()
                        .orElse(members.get(0).claim());
            }
            return members.stream()
                    .filter(member -> member.targetVector() != null)
                    .max(Comparator.comparingDouble(member -> cosineSimilarity(member.targetVector(), center)))
                    .map(ClusterMember::claim)
                    .orElse(members.get(0).claim());
        }

        private double[] recomputeCenter() {
            if (vectors.isEmpty()) {
                return null;
            }
            int dimension = vectors.get(0).length;
            double[] average = new double[dimension];
            for (double[] vector : vectors) {
                for (int i = 0; i < dimension; i++) {
                    average[i] += vector[i];
                }
            }
            for (int i = 0; i < dimension; i++) {
                average[i] /= vectors.size();
            }
            return normalize(average);
        }

        private List<ClusterMember> members() {
            return members;
        }

        private double[] center() {
            return center;
        }

        private FeedbackClaim representative() {
            return representative;
        }

        private LocalDateTime latestAt() {
            return latestAt;
        }

        private String groupId() {
            return groupId;
        }

        private String displayModule() {
            return displayModule;
        }

        private java.util.Set<String> rawIds() {
            return rawIds;
        }

        private String chooseDisplayModule() {
            Map<String, Long> counts = members.stream()
                    .map(ClusterMember::targetLabel)
                    .filter(label -> label != null && !label.isBlank())
                    .collect(Collectors.groupingBy(label -> label, LinkedHashMap::new, Collectors.counting()));
            if (counts.isEmpty()) {
                return representative != null ? representative.getModule() : "";
            }
            long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
            List<String> candidates = counts.entrySet().stream()
                    .filter(entry -> entry.getValue() == max)
                    .map(Map.Entry::getKey)
                    .toList();
            if (candidates.size() == 1 || center == null) {
                return candidates.get(0);
            }
            return members.stream()
                    .filter(member -> candidates.contains(member.targetLabel()))
                    .filter(member -> member.targetVector() != null)
                    .max(Comparator.comparingDouble(member -> cosineSimilarity(member.targetVector(), center)))
                    .map(ClusterMember::targetLabel)
                    .orElse(candidates.get(0));
        }
    }

    private record TargetSpec(String label, String embeddingText) {
    }

    private record ClusterMember(FeedbackClaim claim,
                                 double[] targetVector,
                                 String targetLabel,
                                 String normalizedTargetLabel) {
    }
}
