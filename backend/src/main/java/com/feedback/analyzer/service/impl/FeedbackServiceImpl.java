package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackAnalyzed;
import com.feedback.analyzer.entity.FeedbackClaim;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.entity.FeedbackIssueDocument;
import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.entity.IssueTimeline;
import com.feedback.analyzer.model.dto.AiClaimResult;
import com.feedback.analyzer.model.dto.AiAnalysisResult;
import com.feedback.analyzer.model.dto.AiFeedbackDecision;
import com.feedback.analyzer.model.dto.BugInitialTriageDecision;
import com.feedback.analyzer.model.dto.FeedbackQueryRequest;
import com.feedback.analyzer.model.dto.FeedbackSubmitRequest;
import com.feedback.analyzer.model.enums.FeedbackClaimStatusEnum;
import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.repository.*;
import com.feedback.analyzer.service.AiAnalysisService;
import com.feedback.analyzer.service.FeedbackService;
import com.feedback.analyzer.service.FeedbackRawIngestionService;
import com.feedback.analyzer.service.IssueRecallService;
import com.feedback.analyzer.service.ZenTaoService;
import com.feedback.analyzer.service.ai.BugInitialTriageService;
import com.feedback.analyzer.service.ai.IssueProgressDecisionService;
import com.feedback.analyzer.util.TextCleanUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements FeedbackService {

    private final FeedbackRawRepository rawRepo;
    private final FeedbackAnalyzedRepository analyzedRepo;
    private final FeedbackClaimRepository claimRepo;
    private final FeedbackIssueRepository issueRepo;
    private final IssueTimelineRepository timelineRepo;
    private final AiAnalysisService aiService;
    private final RedissonClient redisson;
    private final FeedbackIssueEsRepository issueEsRepo;
    private final IssueRecallService issueRecallService;
    private final BugInitialTriageService bugInitialTriageService;
    private final IssueProgressDecisionService issueProgressDecisionService;
    private final ZenTaoService zenTaoService;
    private final PublicReviewRunCoordinator publicReviewRunCoordinator;
    private final FeedbackRawIngestionService rawIngestionService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @org.springframework.beans.factory.annotation.Value("${dedup.similarity-threshold:0.80}")
    private double similarityThreshold;

    @org.springframework.beans.factory.annotation.Value("${dedup.module-mismatch-block-enabled:false}")
    private boolean moduleMismatchBlockEnabled;

    @org.springframework.beans.factory.annotation.Value("${feedback.processing.analyzing-timeout-minutes:60}")
    private long analyzingTimeoutMinutes;

    private static final int KNN_RECALL_LIMIT = 10;
    private static final int DECISION_EVIDENCE_LIMIT = 3;

    @Override
    public FeedbackRaw submit(Long productId, FeedbackSubmitRequest request) {
        return rawIngestionService.saveAndPublish(buildRaw(productId, request));
    }

    @Override
    public FeedbackRaw submitSync(Long productId, FeedbackSubmitRequest request) {
        FeedbackRaw saved = rawIngestionService.save(buildRaw(productId, request));
        processRaw(saved.getId());
        return rawRepo.findById(saved.getId()).orElse(saved);
    }

    private FeedbackRaw buildRaw(Long productId, FeedbackSubmitRequest request) {
        return FeedbackRaw.builder()
                .id(generateId("RAW"))
                .productId(productId)
                .star(request.getStar())
                .channel(request.getChannel())
                .rawContent(request.getRawContent())
                .userId(request.getUserId())
                .userName(request.getUserName())
                .appVersion(request.getAppVersion())
                .deviceInfo(request.getDeviceInfo())
                .feedbackTime(request.getFeedbackTime() != null ? request.getFeedbackTime() : LocalDateTime.now())
                .status(FeedbackStatusEnum.RAW)
                .build();
    }

    @Override
    public void processRaw(String rawId) {
        processRawInternal(rawId, false, false);
    }

    @Override
    public void retryTimedOutAnalyzing(String rawId) {
        processRawInternal(rawId, false, true);
    }

    private void processRawInternal(String rawId, boolean deferProgress, boolean recoverTimedOutAnalyzing) {
        RLock lock = redisson.getLock("feedback:lock:" + rawId);
        FeedbackRaw handledRaw = null;
        try {
            if (!lock.tryLock(5, TimeUnit.SECONDS)) {
                log.warn("Failed to acquire lock for: {}", rawId);
                return;
            }

            FeedbackRaw raw = rawRepo.findById(rawId).orElse(null);
            if (raw == null) {
                return;
            }

            if (recoverTimedOutAnalyzing) {
                if (raw.getStatus() != FeedbackStatusEnum.ANALYZING || !isTimedOutAnalyzing(raw)) {
                    return;
                }
                handledRaw = resetTimedOutAnalyzing(rawId);
            } else {
                if (raw.getStatus() != FeedbackStatusEnum.RAW) {
                    return;
                }
                handledRaw = markAnalyzing(rawId);
            }

            if (handledRaw == null) {
                return;
            }

            if (TextCleanUtil.isLowQuality(handledRaw.getRawContent())) {
                markTerminalStatus(rawId, FeedbackStatusEnum.LOW_QUALITY);
                return;
            }

            AiAnalysisResult result = analyzeSingleRaw(handledRaw);
            persistAnalysisResult(rawId, handledRaw, result, deferProgress);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Process interrupted for: {}", rawId);
            revertToRaw(rawId, "处理被中断");
        } catch (Exception e) {
            log.error("Failed to process feedback raw {}", rawId, e);
            revertToRaw(rawId, defaultIfBlank(e.getMessage(), "处理失败"));
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            if (handledRaw != null && handledRaw.getCollectionRunId() != null) {
                publicReviewRunCoordinator.onFeedbackHandled(handledRaw.getCollectionRunId());
            }
        }
    }

    @Override
    public void batchProcessRaw(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return;
        }
        rawIds.forEach(this::processRaw);
    }

    private AiAnalysisResult analyzeSingleRaw(FeedbackRaw raw) {
        List<Map<String, String>> batchInput = List.of(buildBatchInput(raw));
        List<AiAnalysisResult> results = aiService.batchAnalyze(batchInput);
        if (results == null || results.isEmpty() || results.get(0) == null) {
            throw new IllegalStateException("AI analysis returned empty result");
        }
        return results.get(0);
    }

    private Map<String, String> buildBatchInput(FeedbackRaw raw) {
        Map<String, String> input = new HashMap<>();
        input.put("id", raw.getId());
        input.put("content", raw.getRawContent());
        input.put("version", raw.getAppVersion() != null ? raw.getAppVersion() : "");
        input.put("device", raw.getDeviceInfo() != null ? raw.getDeviceInfo() : "");
        return input;
    }

    private boolean isTimedOutAnalyzing(FeedbackRaw raw) {
        LocalDateTime startedAt = raw.getProcessingStartedAt();
        return startedAt == null || startedAt.isBefore(LocalDateTime.now().minusMinutes(analyzingTimeoutMinutes));
    }

    private FeedbackRaw markAnalyzing(String rawId) {
        return transactionTemplate().execute(status -> rawRepo.findById(rawId)
                .filter(raw -> raw.getStatus() == FeedbackStatusEnum.RAW)
                .map(raw -> {
                    raw.setStatus(FeedbackStatusEnum.ANALYZING);
                    raw.setProcessingStartedAt(LocalDateTime.now());
                    raw.setProcessingError(null);
                    return rawRepo.save(raw);
                })
                .orElse(null));
    }

    private FeedbackRaw resetTimedOutAnalyzing(String rawId) {
        return transactionTemplate().execute(status -> rawRepo.findById(rawId)
                .filter(raw -> raw.getStatus() == FeedbackStatusEnum.ANALYZING)
                .filter(this::isTimedOutAnalyzing)
                .map(raw -> {
                    raw.setProcessingStartedAt(LocalDateTime.now());
                    raw.setProcessingError(null);
                    return rawRepo.save(raw);
                })
                .orElse(null));
    }

    private void markTerminalStatus(String rawId, FeedbackStatusEnum terminalStatus) {
        transactionTemplate().executeWithoutResult(status -> rawRepo.findById(rawId).ifPresent(raw -> {
            if (raw.getStatus() == FeedbackStatusEnum.ANALYZING || raw.getStatus() == FeedbackStatusEnum.RAW) {
                raw.setStatus(terminalStatus);
                raw.setProcessingStartedAt(null);
                raw.setProcessingError(null);
                rawRepo.save(raw);
            }
        }));
    }

    private void revertToRaw(String rawId, String errorMessage) {
        transactionTemplate().executeWithoutResult(status -> rawRepo.findById(rawId).ifPresent(raw -> {
            if (raw.getStatus() == FeedbackStatusEnum.ANALYZING) {
                raw.setStatus(FeedbackStatusEnum.RAW);
                raw.setProcessingStartedAt(null);
                raw.setProcessingError(trimToLength(defaultIfBlank(errorMessage, "处理失败"), 2000));
                rawRepo.save(raw);
            }
        }));
    }

    private void persistAnalysisResult(String rawId, FeedbackRaw rawSnapshot, AiAnalysisResult result, boolean deferProgress) {
        Set<String> postCommitBugIssueIds = new LinkedHashSet<>();
        transactionTemplate().executeWithoutResult(status -> {
            FeedbackRaw raw = rawRepo.findById(rawId)
                    .orElseThrow(() -> new IllegalStateException("Feedback raw not found: " + rawId));
            if (raw.getStatus() != FeedbackStatusEnum.ANALYZING) {
                return;
            }

            List<AiClaimResult> claimResults = normalizeClaims(result);
            if (Boolean.TRUE.equals(result.getIsLowQuality()) || claimResults.isEmpty()) {
                raw.setStatus(FeedbackStatusEnum.LOW_QUALITY);
                raw.setProcessingStartedAt(null);
                raw.setProcessingError(null);
                rawRepo.save(raw);
                return;
            }

            String analyzedId = generateId("FA");
            FeedbackCategoryEnum analyzedCategory = parseCategory(result.getCategory());
            FeedbackAnalyzed analyzed = FeedbackAnalyzed.builder()
                    .id(analyzedId)
                    .productId(raw.getProductId())
                    .rawId(raw.getId())
                    .category(analyzedCategory)
                    .module(result.getModule())
                    .keywords(String.join(",", result.getKeywords() != null ? result.getKeywords() : List.of()))
                    .summary(result.getSummary())
                    .status(FeedbackStatusEnum.ANALYZED)
                    .build();

            analyzed = analyzedRepo.save(analyzed);

            Set<String> countedIssueIdsForRaw = new HashSet<>();
            List<FeedbackClaim> processedClaims = new ArrayList<>();
            boolean hasValidClaim = false;
            boolean publicReviewBatch = raw.getCollectionRunId() != null;
            boolean deferIssueProgress = true;
            for (int claimIndex = 0; claimIndex < claimResults.size(); claimIndex++) {
                FeedbackClaim claim = buildClaim(raw, analyzed, claimResults.get(claimIndex), claimIndex);
                claim = claimRepo.save(claim);

                if (claim.getCategory() == FeedbackCategoryEnum.PRAISE) {
                    handlePraiseClaim(claim);
                    hasValidClaim = hasValidClaim || claim.getStatus() == FeedbackClaimStatusEnum.RECORDED;
                    processedClaims.add(claim);
                    continue;
                }

                List<Double> claimEmbedding = generateClaimEmbedding(claim, rawSnapshot);
                serializeEmbedding(claimEmbedding, claim::setEmbeddingVector, "claim");
                claim = claimRepo.save(claim);

                List<Double> issueEmbedding = refreshClaimEmbedding(claim, claimEmbedding);
                FeedbackAnalyzed claimView = buildAnalyzedView(analyzed, claim);
                String issueId = switch (claim.getCategory()) {
                    case BUG -> handleBugClaim(claim, claimView, issueEmbedding, countedIssueIdsForRaw, deferIssueProgress);
                    case SUGGESTION -> handleSuggestionClaim(claim, claimView, issueEmbedding, countedIssueIdsForRaw, deferIssueProgress);
                    case PRAISE -> {
                        handlePraiseClaim(claim);
                        yield null;
                    }
                };
                claim.setIssueId(issueId);
                claimRepo.save(claim);
                if (!publicReviewBatch && !deferProgress && claim.getCategory() == FeedbackCategoryEnum.BUG && issueId != null) {
                    postCommitBugIssueIds.add(issueId);
                }
                hasValidClaim = hasValidClaim || issueId != null || claim.getStatus() == FeedbackClaimStatusEnum.RECORDED;
                processedClaims.add(claim);
            }

            FeedbackClaim primaryClaim = selectPrimaryClaim(processedClaims);
            if (primaryClaim != null) {
                copyClaimToAnalyzed(analyzed, primaryClaim);
            }
            analyzed.setStatus(hasValidClaim ? FeedbackStatusEnum.ANALYZED : FeedbackStatusEnum.LOW_QUALITY);
            analyzedRepo.save(analyzed);

            raw.setStatus(hasValidClaim ? FeedbackStatusEnum.ANALYZED : FeedbackStatusEnum.LOW_QUALITY);
            raw.setProcessingStartedAt(null);
            raw.setProcessingError(null);
            rawRepo.save(raw);

            registerAfterCommitBugProgress(postCommitBugIssueIds);
        });
    }

    private void registerAfterCommitBugProgress(Set<String> issueIds) {
        if (issueIds.isEmpty()) {
            return;
        }
        List<String> ids = List.copyOf(issueIds);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            ids.forEach(this::progressBugIssue);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ids.forEach(FeedbackServiceImpl.this::progressBugIssue);
            }
        });
    }

    private void progressBugIssue(String issueId) {
        try {
            FeedbackIssue issue = issueRepo.findById(issueId).orElse(null);
            if (issue != null && issue.getCategory() == FeedbackCategoryEnum.BUG) {
                issueProgressDecisionService.progress(issue);
            }
        } catch (Exception e) {
            log.warn("Failed to progress issue after feedback analysis: {}", issueId, e);
        }
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private List<AiClaimResult> normalizeClaims(AiAnalysisResult result) {
        if (result == null) {
            return List.of();
        }
        List<AiClaimResult> source = result.getClaims();
        if ((source == null || source.isEmpty()) && result.getSummary() != null && !result.getSummary().isBlank()) {
            AiClaimResult fallback = new AiClaimResult();
            fallback.setCategory(result.getCategory());
            fallback.setModule(result.getModule());
            fallback.setKeywords(result.getKeywords());
            fallback.setSummary(result.getSummary());
            fallback.setContent(result.getSummary());
            fallback.setIsPrimary(true);
            source = List.of(fallback);
        }
        if (source == null || source.isEmpty()) {
            return List.of();
        }

        List<AiClaimResult> claims = new ArrayList<>();
        boolean primaryAssigned = false;
        for (AiClaimResult claim : source) {
            if (claim == null || claim.getSummary() == null || claim.getSummary().isBlank()) {
                continue;
            }
            if (claim.getCategory() == null || claim.getCategory().isBlank()) {
                claim.setCategory(result.getCategory());
            }
            if (claim.getModule() == null || claim.getModule().isBlank()) {
                claim.setModule(result.getModule());
            }
            claim.setModule(normalizeModuleName(claim.getModule()));
            if (claim.getKeywords() == null || claim.getKeywords().isEmpty()) {
                claim.setKeywords(result.getKeywords());
            }
            if (claim.getContent() == null || claim.getContent().isBlank()) {
                claim.setContent(claim.getSummary());
            }
            boolean primary = Boolean.TRUE.equals(claim.getIsPrimary()) && !primaryAssigned;
            claim.setIsPrimary(primary);
            primaryAssigned = primaryAssigned || primary;
            claims.add(claim);
            if (claims.size() >= 3) {
                break;
            }
        }
        if (!claims.isEmpty() && claims.stream().noneMatch(claim -> Boolean.TRUE.equals(claim.getIsPrimary()))) {
            claims.get(0).setIsPrimary(true);
        }
        return claims;
    }

    private FeedbackClaim buildClaim(FeedbackRaw raw,
                                     FeedbackAnalyzed analyzed,
                                     AiClaimResult result,
                                     int claimIndex) {
        String keywords = joinKeywords(result.getKeywords());
        FeedbackCategoryEnum category = parseCategory(result.getCategory());
        return FeedbackClaim.builder()
                .id(generateId("FC"))
                .rawId(raw.getId())
                .analyzedId(analyzed.getId())
                .productId(raw.getProductId())
                .claimIndex(claimIndex)
                .primaryClaim(Boolean.TRUE.equals(result.getIsPrimary()))
                .category(category)
                .module(normalizeModuleName(result.getModule()))
                .keywords(trimToLength(keywords, 256))
                .summary(result.getSummary().trim())
                .content(trimToLength(defaultIfBlank(result.getContent(), result.getSummary()), 2000))
                .status(FeedbackClaimStatusEnum.PENDING)
                .build();
    }

    private FeedbackAnalyzed buildAnalyzedView(FeedbackAnalyzed analyzed, FeedbackClaim claim) {
        return FeedbackAnalyzed.builder()
                .id(analyzed.getId())
                .rawId(analyzed.getRawId())
                .productId(analyzed.getProductId())
                .issueId(claim.getIssueId())
                .category(claim.getCategory())
                .module(claim.getModule())
                .keywords(claim.getKeywords())
                .summary(claim.getSummary())
                .embeddingVector(claim.getEmbeddingVector())
                .status(FeedbackStatusEnum.ANALYZED)
                .build();
    }

    private void copyClaimToAnalyzed(FeedbackAnalyzed analyzed, FeedbackClaim claim) {
        analyzed.setIssueId(claim.getIssueId());
        analyzed.setCategory(claim.getCategory());
        analyzed.setModule(claim.getModule());
        analyzed.setKeywords(claim.getKeywords());
        analyzed.setSummary(claim.getSummary());
        analyzed.setEmbeddingVector(claim.getEmbeddingVector());
    }

    private FeedbackClaim selectPrimaryClaim(List<FeedbackClaim> claims) {
        if (claims == null || claims.isEmpty()) {
            return null;
        }
        return claims.stream()
                .filter(claim -> claim.getIssueId() != null && !claim.getIssueId().isBlank())
                .filter(claim -> Boolean.TRUE.equals(claim.getPrimaryClaim()))
                .findFirst()
                .orElseGet(() -> claims.stream()
                        .filter(claim -> claim.getIssueId() != null && !claim.getIssueId().isBlank())
                        .findFirst()
                        .orElse(null));
    }

    private List<Double> generateClaimEmbedding(FeedbackClaim claim, FeedbackRaw raw) {
        String combined = buildClaimEmbeddingText(claim, raw);
        if (combined.isBlank()) {
            return List.of();
        }
        return aiService.generateEmbedding(combined);
    }

    private List<Double> refreshClaimEmbedding(FeedbackClaim claim, List<Double> fallback) {
        String combined = buildClaimEmbeddingText(claim, null);
        if (combined.isBlank()) {
            return fallback != null ? fallback : List.of();
        }
        List<Double> generated = aiService.generateEmbedding(combined);
        if (generated.isEmpty()) {
            return fallback != null ? fallback : List.of();
        }
        serializeEmbedding(generated, claim::setEmbeddingVector, "claim refreshed");
        claimRepo.save(claim);
        return generated;
    }

    private String buildClaimEmbeddingText(FeedbackClaim claim, FeedbackRaw raw) {
        return ((claim.getCategory() != null ? claim.getCategory().name() : "") + " "
                + (claim.getModule() != null ? claim.getModule() : "") + " "
                + (claim.getSummary() != null ? claim.getSummary() : "") + " "
                + (claim.getKeywords() != null ? claim.getKeywords().replace(",", " ") : "") + " "
                + (claim.getContent() != null ? claim.getContent() : "")).trim();
    }

    private void serializeEmbedding(List<Double> embedding,
                                    java.util.function.Consumer<String> setter,
                                    String label) {
        if (embedding == null || embedding.isEmpty()) {
            return;
        }
        try {
            setter.accept(objectMapper.writeValueAsString(embedding));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize {} embedding", label, e);
        }
    }

    private String applyClaimTriageDecision(FeedbackClaim claim,
                                            List<Double> embedding,
                                            AiFeedbackDecision decision,
                                            Set<String> countedIssueIdsForRaw,
                                            boolean deferProgress) {
        return applyClaimTriageDecision(claim, embedding, decision, countedIssueIdsForRaw, deferProgress, List.of());
    }

    private String applyClaimTriageDecision(FeedbackClaim claim,
                                            List<Double> embedding,
                                            AiFeedbackDecision decision,
                                            Set<String> countedIssueIdsForRaw,
                                            boolean deferProgress,
                                            List<Map<String, Object>> decisionEvidence) {
        if (decision != null && "IGNORE_NOISE".equals(decision.getAction())) {
            claim.setDecisionAction("CREATE_ISSUE");
            claim.setDecisionReason("claim 已完成分类，BUG/SUGGESTION 不再二次忽略，按保守新建处理");
            claim.setDecisionConfidence(decision.getConfidence());
            log.info("Decision returned ignore for claim {}, converted to create: {}",
                    claim.getId(), decision.getReason());
        }
        if (decision != null && "MERGE_ISSUE".equals(decision.getAction())
                && decision.getIssueId() != null && !decision.getIssueId().isBlank()) {
            FeedbackIssue issue = issueRepo.findById(decision.getIssueId()).orElse(null);
            if (canMergeIssue(claim, issue)) {
                log.info("Rule merged claim {} into issue {}: {}",
                        claim.getId(), issue.getId(), decision.getReason());
                linkClaimToIssue(claim, issue, countedIssueIdsForRaw, deferProgress, decisionEvidence);
                claim.setStatus(FeedbackClaimStatusEnum.MERGED);
                return issue.getId();
            }
        }
        log.info("Rule created issue for claim {}: {}",
                claim.getId(), decision != null ? decision.getReason() : "no decision");
        String issueId = createNewIssue(claim, embedding, deferProgress);
        claim.setStatus(FeedbackClaimStatusEnum.CREATED);
        return issueId;
    }

    private String handleBugClaim(FeedbackClaim claim,
                                  FeedbackAnalyzed claimView,
                                  List<Double> embedding,
                                  Set<String> countedIssueIdsForRaw,
                                  boolean deferProgress) {
        IssueRecallService.RecallResult recall = issueRecallService.search(
                claimView, embedding, KNN_RECALL_LIMIT, similarityThreshold, FeedbackCategoryEnum.BUG);
        List<Map<String, Object>> candidates = recall.candidates();
        AiFeedbackDecision decision = decideByRecallRule(claim, candidates);
        applyDecisionFields(claim, decision);
        return applyClaimTriageDecision(claim, embedding, decision, countedIssueIdsForRaw, deferProgress, candidates);
    }

    private String handleSuggestionClaim(FeedbackClaim claim,
                                         FeedbackAnalyzed claimView,
                                         List<Double> embedding,
                                         Set<String> countedIssueIdsForRaw,
                                         boolean deferProgress) {
        IssueRecallService.RecallResult recall = issueRecallService.search(
                claimView, embedding, KNN_RECALL_LIMIT, similarityThreshold, FeedbackCategoryEnum.SUGGESTION);
        List<Map<String, Object>> candidates = recall.candidates();
        AiFeedbackDecision decision = decideByRecallRule(claim, candidates);
        applyDecisionFields(claim, decision);
        return applyClaimTriageDecision(claim, embedding, decision, countedIssueIdsForRaw, deferProgress, candidates);
    }

    private AiFeedbackDecision decideByRecallRule(FeedbackClaim claim, List<Map<String, Object>> candidates) {
        Map<String, Object> top1 = candidates != null && !candidates.isEmpty() ? candidates.get(0) : null;
        if (top1 == null) {
            return ruleCreateDecision(claim, "未召回到强相似候选");
        }

        String issueId = stringValue(top1.get("issueId"));
        String issueModule = stringValue(top1.get("module"));
        double score = doubleValue(top1.get("score"));
        if (issueId.isBlank()) {
            return ruleCreateDecision(claim, "Top1 候选缺少 issueId");
        }
        if (claim.getCategory() == FeedbackCategoryEnum.BUG
                && moduleMismatchBlockEnabled && isExplicitModuleMismatch(claim.getModule(), issueModule)) {
            return ruleCreateDecision(claim, String.format(Locale.ROOT,
                    "Top1 相似度 %.4f 达到阈值，但反馈模块“%s”和候选模块“%s”明确不一致，保守新建",
                    score, claim.getModule(), issueModule));
        }
        String action = claim.getCategory() == FeedbackCategoryEnum.SUGGESTION ? "MERGE_ISSUE" : "MERGE_ISSUE";
        String verb = claim.getCategory() == FeedbackCategoryEnum.SUGGESTION ? "聚合" : "归并";
        return AiFeedbackDecision.builder()
                .action(action)
                .issueId(issueId)
                .confidence(Math.min(0.99, score))
                .reason(String.format(Locale.ROOT,
                        "Top1 已通过强候选阈值过滤，相似度 %.4f，按规则自动%s",
                        score, verb))
                .category(claim.getCategory() != null ? claim.getCategory().name() : FeedbackCategoryEnum.SUGGESTION.name())
                .module(claim.getModule())
                .summary(claim.getSummary())
                .keywords(splitKeywords(claim.getKeywords()))
                .build();
    }

    private AiFeedbackDecision ruleCreateDecision(FeedbackClaim claim, String reason) {
        return AiFeedbackDecision.builder()
                .action("CREATE_ISSUE")
                .confidence(1.0)
                .reason(reason)
                .category(claim != null && claim.getCategory() != null
                        ? claim.getCategory().name() : FeedbackCategoryEnum.SUGGESTION.name())
                .build();
    }

    private void handlePraiseClaim(FeedbackClaim claim) {
        claim.setIssueId(null);
        if (isSpecificPraiseClaim(claim)) {
            claim.setStatus(FeedbackClaimStatusEnum.RECORDED);
            claim.setDecisionAction("RECORD_PRAISE");
            claim.setDecisionReason("有明确对象的正向反馈，记录为好评亮点");
            claim.setDecisionConfidence(claim.getDecisionConfidence() != null ? claim.getDecisionConfidence() : 1.0);
            log.info("Recorded praise claim {}: {}", claim.getId(), claim.getSummary());
        } else {
            claim.setStatus(FeedbackClaimStatusEnum.IGNORED);
            claim.setDecisionAction("IGNORE_NOISE");
            claim.setDecisionReason("泛泛正向短评缺少明确对象，不进入好评统计");
            claim.setDecisionConfidence(claim.getDecisionConfidence() != null ? claim.getDecisionConfidence() : 0.8);
            log.info("Ignored vague praise claim {}: {}", claim.getId(), claim.getSummary());
        }
        claimRepo.save(claim);
    }

    private boolean isSpecificPraiseClaim(FeedbackClaim claim) {
        if (claim == null || claim.getCategory() != FeedbackCategoryEnum.PRAISE) {
            return false;
        }
        String summary = normalizeTextForRule(claim.getSummary());
        String module = normalizeTextForRule(claim.getModule());
        String keywords = normalizeTextForRule(claim.getKeywords());
        String combined = (module + " " + summary + " " + keywords).trim();
        if (combined.isBlank()) {
            return false;
        }

        Set<String> vaguePraise = Set.of(
                "好", "挺好", "很好", "不错", "还行", "可以", "满意", "牛", "666",
                "verygood", "good", "great", "nice", "ok"
        );
        String compactSummary = summary.replaceAll("\\s+", "");
        if (vaguePraise.contains(compactSummary)) {
            return false;
        }

        boolean meaningfulModule = !module.isBlank()
                && !Set.of("整体", "总体", "其他", "未知", "通用", "默认", "未分类", "无").contains(module);
        boolean meaningfulKeyword = Arrays.stream(keywords.split("[,，\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .anyMatch(s -> !vaguePraise.contains(s) && s.length() >= 2);
        boolean objectMentioned = containsAny(combined, List.of(
                "模块", "功能", "页面", "流程", "服务", "客服", "接口", "导入", "导出", "上传", "下载",
                "登录", "注册", "支付", "退款", "搜索", "筛选", "报表", "通知", "配置", "同步",
                "性能", "速度", "稳定", "画面", "视觉", "交互", "操作", "手感", "文档", "版本", "更新"
        ));
        return meaningfulModule || meaningfulKeyword || objectMentioned;
    }

    private boolean containsAny(String value, List<String> needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return needles.stream().anyMatch(value::contains);
    }

    private String normalizeTextForRule(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeModuleName(String value) {
        if (value == null || value.isBlank()) {
            return "未分类";
        }
        String module = value.trim()
                .replace('：', ':')
                .replaceAll("\\s+", "");
        Map<String, String> aliases = Map.ofEntries(
                Map.entry("游戏战斗系统", "战斗系统"),
                Map.entry("战斗系统模块", "战斗系统"),
                Map.entry("boss设计", "Boss设计"),
                Map.entry("BOSS设计", "Boss设计"),
                Map.entry("Boss战斗", "Boss战"),
                Map.entry("boss战", "Boss战")
        );
        return aliases.getOrDefault(module, module);
    }

    private boolean isExplicitModuleMismatch(String claimModule, String issueModule) {
        String left = normalizeModuleForRule(claimModule);
        String right = normalizeModuleForRule(issueModule);
        if (!isMeaningfulModule(left) || !isMeaningfulModule(right)) {
            return false;
        }
        return !left.equals(right) && !left.contains(right) && !right.contains(left);
    }

    private String normalizeModuleForRule(String value) {
        String normalized = normalizeTextForRule(value)
                .replace("模块", "")
                .replace("功能", "")
                .replace("页面", "")
                .replace("流程", "")
                .trim();
        return normalized.replaceAll("\\s+", "");
    }

    private boolean isMeaningfulModule(String module) {
        if (module == null || module.isBlank()) {
            return false;
        }
        String normalized = normalizeModuleForRule(module);
        return !normalized.isBlank()
                && !Set.of("整体", "总体", "其他", "未知", "通用", "默认", "未分类", "无", "none", "unknown", "general")
                .contains(normalized);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private List<String> splitKeywords(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return List.of();
        }
        return Arrays.stream(keywords.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private void applyDecisionFields(FeedbackClaim claim, AiFeedbackDecision decision) {
        if (decision == null) {
            return;
        }
        claim.setDecisionAction(decision.getAction());
        claim.setDecisionReason(decision.getReason());
        claim.setDecisionConfidence(decision.getConfidence());
        if (decision.getModule() != null && !decision.getModule().isBlank()) {
            claim.setModule(trimToLength(decision.getModule().trim(), 64));
        }
        if (decision.getSummary() != null && !decision.getSummary().isBlank()) {
            claim.setSummary(decision.getSummary().trim());
        }
        if (decision.getKeywords() != null && !decision.getKeywords().isEmpty()) {
            claim.setKeywords(trimToLength(joinKeywords(decision.getKeywords()), 256));
        }
    }

    private void applyDecisionFields(FeedbackAnalyzed analyzed, AiFeedbackDecision decision) {
        if (decision == null) {
            return;
        }
        if (decision.getCategory() != null && !decision.getCategory().isBlank()) {
            analyzed.setCategory(parseCategory(decision.getCategory()));
        }
        if (decision.getModule() != null && !decision.getModule().isBlank()) {
            analyzed.setModule(decision.getModule().trim());
        }
        if (decision.getSummary() != null && !decision.getSummary().isBlank()) {
            analyzed.setSummary(decision.getSummary().trim());
        }
        if (decision.getKeywords() != null && !decision.getKeywords().isEmpty()) {
            String joined = decision.getKeywords().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .collect(Collectors.joining(","));
            analyzed.setKeywords(joined.length() > 256 ? joined.substring(0, 256) : joined);
        }
    }

    private List<Double> refreshEmbedding(FeedbackAnalyzed analyzed, List<Double> fallback) {
        String combined = buildEmbeddingText(analyzed);
        if (combined.isBlank()) {
            return fallback != null ? fallback : List.of();
        }
        List<Double> generated = aiService.generateEmbedding(combined);
        if (generated.isEmpty()) {
            return fallback != null ? fallback : List.of();
        }
        try {
            analyzed.setEmbeddingVector(objectMapper.writeValueAsString(generated));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize refreshed embedding", e);
        }
        return generated;
    }

    private String buildEmbeddingText(FeedbackAnalyzed analyzed) {
        return ((analyzed.getModule() != null ? analyzed.getModule() : "") + " "
                + (analyzed.getSummary() != null ? analyzed.getSummary() : "") + " "
                + (analyzed.getKeywords() != null ? analyzed.getKeywords().replace(",", " ") : "")).trim();
    }

    private boolean canMergeIssue(FeedbackAnalyzed analyzed, FeedbackIssue issue) {
        return issue != null
                && Objects.equals(issue.getProductId(), analyzed.getProductId())
                && Objects.equals(issue.getCategory(), analyzed.getCategory())
                && issue.getStatus() != null
                && !issue.getStatus().isArchivedStatus();
    }

    private boolean canMergeIssue(FeedbackClaim claim, FeedbackIssue issue) {
        return issue != null
                && Objects.equals(issue.getProductId(), claim.getProductId())
                && Objects.equals(issue.getCategory(), claim.getCategory())
                && issue.getStatus() != null
                && !issue.getStatus().isArchivedStatus();
    }

    private String serializeDecisionEvidence(FeedbackClaim claim,
                                             FeedbackIssue currentIssue,
                                             List<Map<String, Object>> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> top = candidates.stream()
                .limit(DECISION_EVIDENCE_LIMIT)
                .map(candidate -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("issueId", stringValue(candidate.get("issueId")));
                    item.put("title", stringValue(candidate.get("title")));
                    item.put("score", doubleValue(candidate.get("score")));
                    item.put("rank", candidate.get("rank"));
                    item.put("current", currentIssue != null
                            && Objects.equals(currentIssue.getId(), stringValue(candidate.get("issueId"))));
                    item.put("claimId", claim != null ? claim.getId() : "");
                    item.put("claimSummary", claim != null ? stringValue(claim.getSummary()) : "");
                    item.put("claimContent", claim != null ? stringValue(claim.getContent()) : "");
                    item.put("module", stringValue(candidate.get("module")));
                    item.put("severity", stringValue(candidate.get("severity")));
                    item.put("reportCount", candidate.get("reportCount"));
                    item.put("summary", stringValue(candidate.get("summary")));
                    item.put("typicalContent", stringValue(candidate.get("typicalContent")));
                    return item;
                })
                .filter(item -> !String.valueOf(item.get("issueId")).isBlank())
                .toList();
        if (top.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(top);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize decision evidence candidates", e);
            return null;
        }
    }

    @Transactional
    public String createNewIssue(FeedbackClaim claim, List<Double> embeddingVec, boolean deferProgress) {
        BugInitialTriageDecision triage = initialTriageForClaim(claim);
        FeedbackIssue issue = FeedbackIssue.builder()
                .id(generateId("ISSUE"))
                .productId(claim.getProductId())
                .title(trimToLength(claim.getSummary() != null ? claim.getSummary() : "未知问题", 256))
                .category(claim.getCategory())
                .module(claim.getModule())
                .severity(normalizeSeverityByCategory(claim.getCategory(), triage.getSeverity()))
                .priority(normalizePriority(triage.getPriority(), "P3"))
                .triageSource(defaultIfBlank(triage.getSource(), "SYSTEM_DEFAULT"))
                .triageReason(formatTriageReason(triage))
                .status(initialIssueStatus(claim.getCategory()))
                .reportCount(1)
                .firstReportAt(LocalDateTime.now())
                .latestReportAt(LocalDateTime.now())
                .affectVersions(extractAppVersion(claim.getRawId()))
                .aiSummary(claim.getSummary())
                .embeddingVector(claim.getEmbeddingVector())
                .suspectedDuplicates(null)
                .typicalContent(defaultIfBlank(claim.getContent(), claim.getSummary()))
                .build();

        issue = issueRepo.save(issue);

        if (embeddingVec != null && !embeddingVec.isEmpty()) {
            double[] vector = embeddingVec.stream().mapToDouble(Double::doubleValue).toArray();
            FeedbackIssueDocument doc = new FeedbackIssueDocument(
                    issue.getId(), issue.getTitle(),
                    issue.getCategory() != null ? issue.getCategory().name() : null,
                    issue.getSeverity(), issue.getProductId(), issue.getCreatedAt(), issue.getReportCount(), vector);
            try {
                issueEsRepo.save(doc);
            } catch (Exception e) {
                log.warn("Failed to index issue in ES: {}", issue.getId(), e);
            }
        }

        IssueTimeline timeline = IssueTimeline.builder()
                .issueId(issue.getId())
                .eventType("created")
                .detail("反馈片段自动创建问题，分类：" + claim.getCategory() + "，摘要：" + claim.getSummary())
                .build();
        timelineRepo.save(timeline);
        if (shouldTriggerBugProgress(issue, deferProgress)) {
            issueProgressDecisionService.progress(issue);
        }

        return issue.getId();
    }

    @Transactional
    public void linkClaimToIssue(FeedbackClaim claim,
                                 FeedbackIssue issue,
                                 Set<String> countedIssueIdsForRaw,
                                 boolean deferProgress) {
        linkClaimToIssue(claim, issue, countedIssueIdsForRaw, deferProgress, List.of());
    }

    @Transactional
    public void linkClaimToIssue(FeedbackClaim claim,
                                 FeedbackIssue issue,
                                 Set<String> countedIssueIdsForRaw,
                                 boolean deferProgress,
                                 List<Map<String, Object>> decisionEvidence) {
        boolean shouldCount = countedIssueIdsForRaw == null || countedIssueIdsForRaw.add(issue.getId());
        if (shouldCount) {
            issue.setReportCount((issue.getReportCount() != null ? issue.getReportCount() : 0) + 1);
            issue.setLatestReportAt(LocalDateTime.now());
            mergeAffectVersion(issue, extractAppVersion(claim.getRawId()));
        }

        String currentSummary = issue.getAiSummary() != null ? issue.getAiSummary() : "";
        if (claim.getSummary() != null && claim.getSummary().length() > currentSummary.length()) {
            issue.setAiSummary(claim.getSummary());
            issue.setTypicalContent(defaultIfBlank(claim.getContent(), claim.getSummary()));
        }
        issue.setSuspectedDuplicates(serializeDecisionEvidence(claim, issue, decisionEvidence));

        issueRepo.save(issue);
        syncIssueDocument(issue);

        IssueTimeline timeline = IssueTimeline.builder()
                .issueId(issue.getId())
                .eventType("feedback_claim_linked")
                .detail("反馈片段自动归并，摘要：" + claim.getSummary())
                .build();
        timelineRepo.save(timeline);
        if (shouldCount && shouldTriggerBugProgress(issue, deferProgress)) {
            syncBugAfterClaimLinked(issue, claim);
        }
    }

    private void syncBugAfterClaimLinked(FeedbackIssue issue, FeedbackClaim claim) {
        if (issue.getRelatedIssue() != null && !issue.getRelatedIssue().isBlank()) {
            boolean appended = zenTaoService.syncBugUpdate(issue, "新增用户反馈归并：" + claim.getSummary());
            timelineRepo.save(IssueTimeline.builder()
                    .issueId(issue.getId())
                    .eventType(appended ? "zentao_comment_appended" : "zentao_comment_skipped")
                    .detail(appended ? "归并反馈后追加禅道备注：" + issue.getRelatedIssue()
                            : "归并反馈后禅道备注未追加：" + issue.getRelatedIssue())
                    .build());
            return;
        }
        issueProgressDecisionService.progress(issue);
    }

    @Transactional
    public String createNewIssue(FeedbackAnalyzed analyzed, List<Double> embeddingVec, boolean deferProgress) {
        BugInitialTriageDecision triage = initialTriageForAnalyzed(analyzed);
        FeedbackIssue issue = FeedbackIssue.builder()
                .id(generateId("ISSUE"))
                .productId(analyzed.getProductId())
                .title(analyzed.getSummary() != null ? analyzed.getSummary() : "未知问题")
                .category(analyzed.getCategory())
                .severity(normalizeSeverityByCategory(analyzed.getCategory(), triage.getSeverity()))
                .priority(normalizePriority(triage.getPriority(), "P3"))
                .triageSource(defaultIfBlank(triage.getSource(), "SYSTEM_DEFAULT"))
                .triageReason(formatTriageReason(triage))
                .status(initialIssueStatus(analyzed.getCategory()))
                .reportCount(1)
                .firstReportAt(LocalDateTime.now())
                .latestReportAt(LocalDateTime.now())
                .affectVersions(extractAppVersion(analyzed.getRawId()))
                .aiSummary(analyzed.getSummary())
                .embeddingVector(analyzed.getEmbeddingVector())
                .typicalContent(analyzed.getSummary())
                .build();

        issue = issueRepo.save(issue);

        if (!embeddingVec.isEmpty()) {
            double[] vector = embeddingVec.stream().mapToDouble(Double::doubleValue).toArray();
            FeedbackIssueDocument doc = new FeedbackIssueDocument(
                    issue.getId(), issue.getTitle(),
                    issue.getCategory() != null ? issue.getCategory().name() : null,
                    issue.getSeverity(), issue.getProductId(), issue.getCreatedAt(), issue.getReportCount(), vector);
            try {
                issueEsRepo.save(doc);
            } catch (Exception e) {
                log.warn("Failed to index issue in ES: {}", issue.getId(), e);
            }
        }

        IssueTimeline timeline = IssueTimeline.builder()
                .issueId(issue.getId())
                .eventType("created")
                .detail("反馈自动创建问题，分类：" + analyzed.getCategory() + "，摘要：" + analyzed.getSummary())
                .build();
        timelineRepo.save(timeline);
        if (shouldTriggerBugProgress(issue, deferProgress)) {
            issueProgressDecisionService.progress(issue);
        }

        return issue.getId();
    }

    @Transactional
    public void linkToIssue(FeedbackAnalyzed analyzed, FeedbackIssue issue, boolean deferProgress) {
        issue.setReportCount(issue.getReportCount() + 1);
        issue.setLatestReportAt(LocalDateTime.now());
        mergeAffectVersion(issue, extractAppVersion(analyzed.getRawId()));

        String currentSummary = issue.getAiSummary() != null ? issue.getAiSummary() : "";
        if (analyzed.getSummary() != null && analyzed.getSummary().length() > currentSummary.length()) {
            issue.setAiSummary(analyzed.getSummary());
            issue.setTypicalContent(analyzed.getSummary());
        }

        if (shouldTriggerBugProgress(issue, deferProgress)) {
            issueProgressDecisionService.progress(issue);
        }
        issueRepo.save(issue);
        syncIssueDocument(issue);

        IssueTimeline timeline = IssueTimeline.builder()
                .issueId(issue.getId())
                .eventType("feedback_linked")
                .detail("反馈自动归并，摘要：" + analyzed.getSummary())
                .build();
        timelineRepo.save(timeline);
    }

    private String extractAppVersion(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        return rawRepo.findById(rawId)
                .map(FeedbackRaw::getAppVersion)
                .filter(version -> version != null && !version.isBlank())
                .orElse(null);
    }

    private void mergeAffectVersion(FeedbackIssue issue, String appVersion) {
        if (appVersion == null || appVersion.isBlank()) {
            return;
        }
        String current = issue.getAffectVersions();
        if (current == null || current.isBlank()) {
            issue.setAffectVersions(appVersion);
            return;
        }
        List<String> versions = Arrays.stream(current.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(ArrayList::new));
        if (!versions.contains(appVersion)) {
            versions.add(appVersion);
            issue.setAffectVersions(String.join(",", versions));
        }
    }

    private boolean shouldRunBugProgress(FeedbackIssue issue) {
        return issue != null && issue.getCategory() == FeedbackCategoryEnum.BUG;
    }

    private boolean shouldTriggerBugProgress(FeedbackIssue issue, boolean deferProgress) {
        return shouldRunBugProgress(issue) && !deferProgress;
    }

    private IssueStatusEnum initialIssueStatus(FeedbackCategoryEnum category) {
        return category == FeedbackCategoryEnum.SUGGESTION ? IssueStatusEnum.EVALUATING : IssueStatusEnum.OPEN;
    }

    @Override
    public FeedbackRaw getRawStatus(String rawId) {
        return rawRepo.findById(rawId).orElse(null);
    }

    @Override
    public FeedbackAnalyzed getAnalyzed(String id) {
        return analyzedRepo.findById(id).orElse(null);
    }

    @Override
    public Page<FeedbackAnalyzed> search(FeedbackQueryRequest req) {
        Pageable pageable = PageRequest.of(req.getPage() - 1, req.getSize(), Sort.by(Sort.Direction.DESC, "analyzedAt"));
        FeedbackCategoryEnum categoryFilter = req.getCategory() != null ? parseCategory(req.getCategory()) : null;
        return analyzedRepo.searchFeedbacks(
                req.getProductId(),
                categoryFilter,
                req.getModule(), req.getKeyword(),
                req.getStart() != null ? req.getStart() : LocalDateTime.now().minusDays(30),
                req.getEnd() != null ? req.getEnd() : LocalDateTime.now(),
                pageable);
    }

    @Override
    public Page<FeedbackAnalyzed> getByIssueId(String issueId, int page, int size) {
        return analyzedRepo.findByIssueId(issueId, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "analyzedAt")));
    }

    private String generateId(String prefix) {
        return prefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String joinKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return "";
        }
        return keywords.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.joining(","));
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private BugInitialTriageDecision initialTriageForClaim(FeedbackClaim claim) {
        if (claim != null && claim.getCategory() == FeedbackCategoryEnum.BUG) {
            return bugInitialTriageService.decide(claim);
        }
        return BugInitialTriageDecision.systemDefault("Non-Bug issue uses default triage");
    }

    private BugInitialTriageDecision initialTriageForAnalyzed(FeedbackAnalyzed analyzed) {
        if (analyzed != null && analyzed.getCategory() == FeedbackCategoryEnum.BUG) {
            return bugInitialTriageService.decide(analyzed);
        }
        return BugInitialTriageDecision.systemDefault("Non-Bug issue uses default triage");
    }

    private String formatTriageReason(BugInitialTriageDecision triage) {
        if (triage == null) {
            return "";
        }
        String reason = defaultIfBlank(triage.getReason(), "Initial triage");
        return reason + " (confidence=" + String.format(Locale.ROOT, "%.2f", triage.getConfidence()) + ")";
    }

    private String normalizePriority(String priority, String fallback) {
        if (priority == null || priority.isBlank()) {
            return fallback;
        }
        String value = priority.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "P1", "1" -> "P1";
            case "P2", "2" -> "P2";
            case "P3", "3" -> "P3";
            case "P4", "4" -> "P4";
            default -> fallback != null ? fallback : "P3";
        };
    }

    private FeedbackCategoryEnum parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return FeedbackCategoryEnum.SUGGESTION;
        }
        String value = category.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "BUG", "缺陷", "故障", "问题" -> FeedbackCategoryEnum.BUG;
            case "SUGGESTION", "需求", "建议", "优化", "改进" -> FeedbackCategoryEnum.SUGGESTION;
            case "PRAISE", "好评" -> FeedbackCategoryEnum.PRAISE;
            default -> FeedbackCategoryEnum.SUGGESTION;
        };
    }

    @Override
    public int reindexAllIssues() {
        List<FeedbackIssue> issues = issueRepo.findAll();
        int count = 0;
        for (FeedbackIssue issue : issues) {
            try {
                if (issue.getStatus() != null && issue.getStatus().isArchivedStatus()) {
                    issueEsRepo.deleteByIssueId(issue.getId());
                    continue;
                }
                String vecStr = issue.getEmbeddingVector();
                if (vecStr == null || vecStr.isBlank()) {
                    String basis = (issue.getTitle() != null ? issue.getTitle() : "") + " "
                            + (issue.getModule() != null ? issue.getModule() : "") + " "
                            + (issue.getTypicalContent() != null ? issue.getTypicalContent() : "");
                    List<Double> generated = aiService.generateEmbedding(basis.trim());
                    if (generated.isEmpty()) {
                        log.debug("Issue {} has no embedding vector and regeneration failed, skipping", issue.getId());
                        continue;
                    }
                    vecStr = objectMapper.writeValueAsString(generated);
                    issue.setEmbeddingVector(vecStr);
                    issueRepo.save(issue);
                }
                List<Double> vec = objectMapper.readValue(vecStr,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Double>>() {});
                double[] vector = vec.stream().mapToDouble(Double::doubleValue).toArray();
                FeedbackIssueDocument doc = new FeedbackIssueDocument(
                        issue.getId(), issue.getTitle(),
                        issue.getCategory() != null ? issue.getCategory().name() : null,
                        issue.getSeverity(), issue.getProductId(), issue.getCreatedAt(), issue.getReportCount(), vector);
                issueEsRepo.save(doc);
                count++;
            } catch (Exception e) {
                log.error("Failed to reindex issue {}", issue.getId(), e);
            }
        }
        log.info("Reindexed {} issues to ES", count);
        return count;
    }


    private String normalizeSeverity(String severity, String fallback) {
        if (severity == null || severity.isBlank()) {
            return fallback;
        }
        String value = severity.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "CRITICAL", "严重" -> "CRITICAL";
            case "HIGH", "高" -> "HIGH";
            case "MEDIUM", "中" -> "MEDIUM";
            case "LOW", "低" -> "LOW";
            default -> fallback != null ? fallback : "MEDIUM";
        };
    }

    private String normalizeSeverityByCategory(FeedbackCategoryEnum category, String severity) {
        if (category == FeedbackCategoryEnum.BUG) {
            return normalizeSeverity(severity, "MEDIUM");
        }
        return "LOW";
    }

    private void syncIssueDocument(FeedbackIssue issue) {
        try {
            if (issue.getStatus() != null && issue.getStatus().isArchivedStatus()) {
                issueEsRepo.deleteByIssueId(issue.getId());
                return;
            }
            String vecStr = issue.getEmbeddingVector();
            if (vecStr == null || vecStr.isBlank()) {
                return;
            }
            List<Double> vec = objectMapper.readValue(vecStr, new com.fasterxml.jackson.core.type.TypeReference<List<Double>>() {});
            double[] vector = vec.stream().mapToDouble(Double::doubleValue).toArray();
            FeedbackIssueDocument doc = new FeedbackIssueDocument(
                    issue.getId(),
                    issue.getTitle(),
                    issue.getCategory() != null ? issue.getCategory().name() : null,
                    issue.getSeverity(),
                    issue.getProductId(),
                    issue.getCreatedAt(),
                    issue.getReportCount(),
                    vector
            );
            issueEsRepo.save(doc);
        } catch (Exception e) {
            log.warn("Failed to sync issue document {}", issue.getId(), e);
        }
    }
}
