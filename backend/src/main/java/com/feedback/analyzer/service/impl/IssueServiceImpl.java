package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackAnalyzed;
import com.feedback.analyzer.entity.FeedbackClaim;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.entity.FeedbackIssueDocument;
import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.entity.IssueTimeline;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.model.vo.IssueListItemVO;
import com.feedback.analyzer.model.vo.IssueVO;
import com.feedback.analyzer.repository.FeedbackAnalyzedRepository;
import com.feedback.analyzer.repository.FeedbackClaimRepository;
import com.feedback.analyzer.repository.FeedbackIssueEsRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import com.feedback.analyzer.repository.IssueTimelineRepository;
import com.feedback.analyzer.service.IssueService;
import com.feedback.analyzer.service.ZenTaoService;
import com.feedback.analyzer.service.ai.IssueProgressDecisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IssueServiceImpl implements IssueService {

    private final FeedbackIssueRepository issueRepo;
    private final FeedbackIssueEsRepository issueEsRepo;
    private final IssueTimelineRepository timelineRepo;
    private final FeedbackAnalyzedRepository analyzedRepo;
    private final FeedbackClaimRepository claimRepo;
    private final FeedbackRawRepository rawRepo;
    private final IssueProgressDecisionService issueProgressDecisionService;
    private final ZenTaoService zenTaoService;
    private final ObjectMapper objectMapper;

    @Override
    public Page<IssueListItemVO> search(Long productId, String severity, IssueStatusEnum status,
                                       String category, String module, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "reportCount"));
        FeedbackCategoryEnum cat = parseCategory(category);
        if (severity != null && !severity.isBlank() && cat == null) {
            cat = FeedbackCategoryEnum.BUG;
        } else if (severity != null && !severity.isBlank() && cat != FeedbackCategoryEnum.BUG) {
            severity = null;
        }
        return issueRepo.searchIssues(productId, severity, status, cat, module, pageable)
                .map(this::toListItemVO);
    }

    @Override
    public IssueVO getDetail(String issueId) {
        FeedbackIssue issue = issueRepo.findById(issueId)
                .orElseThrow(() -> new RuntimeException("Issue not found: " + issueId));

        List<IssueTimeline> timelines = timelineRepo.findByIssueIdOrderByCreatedAtDesc(issueId);
        List<Map<String, Object>> timelineList = timelines.stream()
                .map(t -> Map.<String, Object>of(
                        "eventType", t.getEventType(),
                        "detail", t.getDetail(),
                        "createdAt", t.getCreatedAt().toString()
                ))
                .collect(Collectors.toList());

        List<FeedbackAnalyzed> samples = analyzedRepo.findByIssueId(
                issueId, PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "analyzedAt"))).getContent();
        Map<String, FeedbackRaw> rawMap = rawRepo.findAllById(
                samples.stream().map(FeedbackAnalyzed::getRawId).toList()).stream()
                .collect(Collectors.toMap(FeedbackRaw::getId, item -> item));
        List<Map<String, Object>> sampleList = samples.stream()
                .map(s -> {
                    FeedbackRaw raw = rawMap.get(s.getRawId());
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("id", s.getId());
                    item.put("category", s.getCategory() != null ? s.getCategory().getLabel() : "");
                    item.put("summary", s.getSummary() != null ? s.getSummary() : "");
                    item.put("analyzedAt", s.getAnalyzedAt() != null ? s.getAnalyzedAt().toString() : "");
                    if (raw != null) {
                        item.put("rawContent", abbreviate(raw.getRawContent(), 240));
                        item.put("channel", value(raw.getChannel()));
                        item.put("sourceType", value(raw.getSourceType()));
                        item.put("appVersion", value(raw.getAppVersion()));
                        item.put("deviceInfo", value(raw.getDeviceInfo()));
                        item.put("userName", value(raw.getUserName()));
                        item.put("star", raw.getStar() != null ? raw.getStar() : "");
                        item.put("feedbackTime", raw.getFeedbackTime() != null ? raw.getFeedbackTime().toString() : "");
                    }
                    return item;
                })
                .collect(Collectors.toList());

        List<Map<String, Object>> mergeEvidence = parseSuspectedDuplicates(issue.getSuspectedDuplicates());
        return IssueVO.builder()
                .id(issue.getId())
                .title(issue.getTitle())
                .category(issue.getCategory() != null ? issue.getCategory().getLabel() : "")
                .module(issue.getModule())
                .severity(displaySeverity(issue.getCategory(), issue.getSeverity()))
                .priority(displayPriority(issue.getCategory(), issue.getPriority()))
                .triageSource(issue.getTriageSource())
                .triageSourceLabel(triageSourceLabel(issue.getTriageSource()))
                .triageReason(issue.getTriageReason())
                .triageReasonDisplay(triageReasonDisplay(issue))
                .status(issue.getStatus().getCode())
                .confirmed(isConfirmed(issue))
                .confirmedLabel(confirmedLabel(issue))
                .reportCount(issue.getReportCount())
                .affectVersions(issue.getAffectVersions())
                .firstReportAt(issue.getFirstReportAt())
                .latestReportAt(issue.getLatestReportAt())
                .aiSummary(issue.getAiSummary())
                .relatedIssue(issue.getRelatedIssue())
                .suspectedDuplicates(mergeEvidence)
                .mergeEvidence(mergeEvidence)
                .typicalContent(issue.getTypicalContent())
                .timeline(timelineList)
                .sampleFeedbacks(sampleList)
                .resolvedAt(issue.getResolvedAt())
                .build();
    }

    @Override
    @Transactional
    public void updateStatus(String issueId, IssueStatusEnum newStatus) {
        FeedbackIssue issue = issueRepo.findById(issueId)
                .orElseThrow(() -> new RuntimeException("Issue not found: " + issueId));

        if (issue.getCategory() == FeedbackCategoryEnum.BUG && newStatus == IssueStatusEnum.ACKNOWLEDGED) {
            confirmBug(issueId, issue);
            return;
        }

        IssueStatusEnum oldStatus = issue.getStatus();
        validateStatusUpdate(issue, newStatus);
        issue.setStatus(newStatus);

        if (newStatus == IssueStatusEnum.RESOLVED || newStatus == IssueStatusEnum.CLOSED) {
            issue.setResolvedAt(LocalDateTime.now());
        } else if (oldStatus == IssueStatusEnum.RESOLVED || oldStatus == IssueStatusEnum.CLOSED) {
            issue.setResolvedAt(null);
        }

        if (issue.getCategory() == FeedbackCategoryEnum.BUG
                && issue.getRelatedIssue() != null && !issue.getRelatedIssue().isBlank()) {
            boolean synced = zenTaoService.updateBugStatus(issue, newStatus);
            if (!synced) {
                throw new IllegalStateException("禅道状态同步失败，本地未保存");
            }
        }

        issueRepo.save(issue);

        IssueTimeline timeline = IssueTimeline.builder()
                .issueId(issueId)
                .eventType("status_changed")
                .detail("状态变更: " + oldStatus.getLabel() + " -> " + newStatus.getLabel())
                .build();
        timelineRepo.save(timeline);

        if (issue.getCategory() == FeedbackCategoryEnum.BUG
                && (issue.getRelatedIssue() == null || issue.getRelatedIssue().isBlank())
                && !newStatus.isArchivedStatus()
                && newStatus != IssueStatusEnum.RESOLVED) {
            issueProgressDecisionService.progress(issue);
        }
    }

    @Override
    @Transactional
    public void confirmIssue(String issueId) {
        FeedbackIssue issue = issueRepo.findById(issueId)
                .orElseThrow(() -> new RuntimeException("Issue not found: " + issueId));
        confirmBug(issueId, issue);
    }

    private void confirmBug(String issueId, FeedbackIssue issue) {
        if (issue.getCategory() != FeedbackCategoryEnum.BUG) {
            throw new IllegalArgumentException("只有 Bug 可以确认");
        }
        if (Boolean.TRUE.equals(issue.getConfirmed())) {
            return;
        }
        if (issue.getRelatedIssue() != null && !issue.getRelatedIssue().isBlank()) {
            boolean synced = zenTaoService.confirmBug(issue);
            if (!synced) {
                throw new IllegalStateException("禅道确认同步失败，本地未保存");
            }
        }
        issue.setConfirmed(true);
        issueRepo.save(issue);
        timelineRepo.save(IssueTimeline.builder()
                .issueId(issueId)
                .eventType("bug_confirmed")
                .detail("Bug 已确认")
                .build());
    }

    @Override
    @Transactional
    public void updateCategory(String issueId, String category) {
        FeedbackIssue issue = issueRepo.findById(issueId)
                .orElseThrow(() -> new RuntimeException("Issue not found: " + issueId));
        FeedbackCategoryEnum parsed = parseCategory(category);
        if (parsed == null) {
            throw new IllegalArgumentException("Unsupported category: " + category);
        }
        if (issue.getCategory() == FeedbackCategoryEnum.PRAISE) {
            throw new IllegalArgumentException("好评记录不允许改成 Bug 或建议");
        }
        if (parsed == FeedbackCategoryEnum.PRAISE) {
            throw new IllegalArgumentException("好评不创建 Issue，不能把 Issue 分类改为好评");
        }
        issue.setCategory(parsed);
        if (parsed != FeedbackCategoryEnum.BUG) {
            issue.setSeverity("LOW");
            issue.setPriority("P4");
            issue.setConfirmed(false);
        }
        if (parsed == FeedbackCategoryEnum.SUGGESTION
                && (issue.getStatus() == null || !issue.getStatus().isSuggestionWorkflowStatus())) {
            issue.setStatus(IssueStatusEnum.EVALUATING);
        }
        if (parsed == FeedbackCategoryEnum.BUG
                && (issue.getStatus() == null || !issue.getStatus().isBugWorkflowStatus())) {
            issue.setStatus(IssueStatusEnum.OPEN);
        }
        issueRepo.save(issue);
        IssueTimeline timeline = IssueTimeline.builder()
                .issueId(issueId).eventType("category_changed")
                .detail("分类改为: " + parsed.getLabel()).build();
        timelineRepo.save(timeline);
    }

    @Override
    @Transactional
    public void updateTriage(String issueId, String severity, String priority, String reason) {
        FeedbackIssue issue = issueRepo.findById(issueId)
                .orElseThrow(() -> new RuntimeException("Issue not found: " + issueId));
        if (issue.getCategory() != FeedbackCategoryEnum.BUG) {
            throw new IllegalArgumentException("只有 Bug Issue 可以修改严重度和优先级");
        }
        String nextSeverity = normalizeSeverity(severity);
        String nextPriority = normalizePriority(priority);
        String oldSeverity = issue.getSeverity();
        String oldPriority = issue.getPriority();
        if (Objects.equals(oldSeverity, nextSeverity) && Objects.equals(oldPriority, nextPriority)) {
            return;
        }

        issue.setSeverity(nextSeverity);
        issue.setPriority(nextPriority);
        issue.setTriageSource("SYSTEM_MANUAL");
        issue.setTriageReason(reason != null && !reason.isBlank() ? reason.trim() : "系统人工修改");

        if (issue.getRelatedIssue() != null && !issue.getRelatedIssue().isBlank()) {
            boolean synced = zenTaoService.updateBugTriage(issue);
            if (!synced) {
                throw new IllegalStateException("禅道等级同步失败，本地未保存");
            }
        }

        issueRepo.save(issue);
        syncIssueDocument(issue);
        timelineRepo.save(IssueTimeline.builder()
                .issueId(issueId)
                .eventType("triage_updated")
                .detail("人工修改等级：" + value(oldSeverity) + "/" + value(oldPriority)
                        + " -> " + nextSeverity + "/" + nextPriority
                        + "；原因：" + issue.getTriageReason())
                .build());
    }

    @Override
    @Transactional
    public void mergeIssue(String sourceIssueId, String targetIssueId) {
        if (targetIssueId == null || targetIssueId.isBlank()) {
            throw new IllegalArgumentException("targetIssueId is required");
        }
        if (Objects.equals(sourceIssueId, targetIssueId)) {
            throw new IllegalArgumentException("source and target issue cannot be the same");
        }

        FeedbackIssue source = issueRepo.findById(sourceIssueId)
                .orElseThrow(() -> new RuntimeException("Source issue not found: " + sourceIssueId));
        FeedbackIssue target = issueRepo.findById(targetIssueId)
                .orElseThrow(() -> new RuntimeException("Target issue not found: " + targetIssueId));

        if (!Objects.equals(source.getProductId(), target.getProductId())) {
            throw new IllegalArgumentException("Only issues in the same product can be merged");
        }
        if (source.getCategory() != target.getCategory()) {
            throw new IllegalArgumentException("Only issues with the same category can be merged");
        }
        if (target.getStatus() == null || target.getStatus().isArchivedStatus()) {
            throw new IllegalArgumentException("不能归并到已归并或旧关闭状态的 Issue");
        }

        List<FeedbackAnalyzed> analyzedItems = analyzedRepo.findByIssueId(sourceIssueId);
        for (FeedbackAnalyzed analyzed : analyzedItems) {
            analyzed.setIssueId(targetIssueId);
        }
        analyzedRepo.saveAll(analyzedItems);

        List<FeedbackClaim> claims = claimRepo.findByIssueId(sourceIssueId);
        for (FeedbackClaim claim : claims) {
            claim.setIssueId(targetIssueId);
        }
        claimRepo.saveAll(claims);

        target.setReportCount((target.getReportCount() != null ? target.getReportCount() : 0)
                + (source.getReportCount() != null ? source.getReportCount() : 0));
        if (source.getFirstReportAt() != null
                && (target.getFirstReportAt() == null || source.getFirstReportAt().isBefore(target.getFirstReportAt()))) {
            target.setFirstReportAt(source.getFirstReportAt());
        }
        if (source.getLatestReportAt() != null
                && (target.getLatestReportAt() == null || source.getLatestReportAt().isAfter(target.getLatestReportAt()))) {
            target.setLatestReportAt(source.getLatestReportAt());
        }
        mergeAffectVersions(target, source.getAffectVersions());
        if ((target.getAiSummary() == null || target.getAiSummary().isBlank()) && source.getAiSummary() != null) {
            target.setAiSummary(source.getAiSummary());
        }
        if ((target.getTypicalContent() == null || target.getTypicalContent().isBlank()) && source.getTypicalContent() != null) {
            target.setTypicalContent(source.getTypicalContent());
        }

        source.setStatus(IssueStatusEnum.MERGED);
        source.setResolvedAt(LocalDateTime.now());
        source.setRelatedIssue(targetIssueId);

        issueRepo.save(target);
        issueRepo.save(source);
        syncIssueDocument(target);
        issueEsRepo.deleteByIssueId(sourceIssueId);

        timelineRepo.save(IssueTimeline.builder()
                .issueId(targetIssueId)
                .eventType("manual_issue_merged")
                .detail("人工归并来源 Issue：" + sourceIssueId + "，追加反馈数：" + source.getReportCount())
                .build());
        timelineRepo.save(IssueTimeline.builder()
                .issueId(sourceIssueId)
                .eventType("manual_issue_merged")
                .detail("人工归并到目标 Issue：" + targetIssueId)
                .build());

        if (target.getCategory() == FeedbackCategoryEnum.BUG) {
            issueProgressDecisionService.progress(target);
        }
    }

    @Override
    @Transactional
    public void reassignClaim(String claimId, String targetIssueId) {
        if (targetIssueId == null || targetIssueId.isBlank()) {
            throw new IllegalArgumentException("targetIssueId is required");
        }
        FeedbackClaim claim = claimRepo.findById(claimId)
                .orElseThrow(() -> new RuntimeException("Claim not found: " + claimId));
        String sourceIssueId = claim.getIssueId();
        if (sourceIssueId == null || sourceIssueId.isBlank()) {
            throw new IllegalArgumentException("该反馈片段尚未归属到 Issue");
        }
        if (Objects.equals(sourceIssueId, targetIssueId)) {
            throw new IllegalArgumentException("目标 Issue 与当前归属一致");
        }
        FeedbackIssue source = issueRepo.findById(sourceIssueId)
                .orElseThrow(() -> new RuntimeException("Source issue not found: " + sourceIssueId));
        FeedbackIssue target = issueRepo.findById(targetIssueId)
                .orElseThrow(() -> new RuntimeException("Target issue not found: " + targetIssueId));
        if (!Objects.equals(source.getProductId(), target.getProductId())
                || !Objects.equals(claim.getProductId(), target.getProductId())) {
            throw new IllegalArgumentException("只能改归属到同一产品下的 Issue");
        }
        if (source.getCategory() != target.getCategory() || claim.getCategory() != target.getCategory()) {
            throw new IllegalArgumentException("只能改归属到同一分类的 Issue");
        }
        if (target.getStatus() == null || target.getStatus().isArchivedStatus()) {
            throw new IllegalArgumentException("不能改归属到已归并或旧关闭状态的 Issue");
        }

        claim.setIssueId(targetIssueId);
        claimRepo.save(claim);
        analyzedRepo.findById(claim.getAnalyzedId()).ifPresent(analyzed -> {
            if (Objects.equals(analyzed.getIssueId(), sourceIssueId)) {
                analyzed.setIssueId(targetIssueId);
                analyzedRepo.save(analyzed);
            }
        });

        recomputeIssueStats(source);
        recomputeIssueStats(target);
        issueRepo.save(source);
        issueRepo.save(target);
        syncIssueDocument(source);
        syncIssueDocument(target);

        timelineRepo.save(IssueTimeline.builder()
                .issueId(sourceIssueId)
                .eventType("claim_reassigned")
                .detail("反馈片段已人工改归属到：" + targetIssueId + "，片段：" + claim.getSummary())
                .build());
        timelineRepo.save(IssueTimeline.builder()
                .issueId(targetIssueId)
                .eventType("claim_reassigned")
                .detail("人工改归属新增反馈片段，来源：" + sourceIssueId + "，片段：" + claim.getSummary())
                .build());

        appendZenTaoReassignComment(source, "该反馈片段已改归属到 " + targetIssueId + "：" + claim.getSummary());
        appendZenTaoReassignComment(target, "新增人工改归属反馈片段，来源 " + sourceIssueId + "：" + claim.getSummary());

        if (target.getCategory() == FeedbackCategoryEnum.BUG) {
            issueProgressDecisionService.progress(target);
        }
    }

    @Override
    @Transactional
    public void linkIssue(String issueId, String issueKey) {
        FeedbackIssue issue = issueRepo.findById(issueId)
                .orElseThrow(() -> new RuntimeException("Issue not found: " + issueId));
        if (issue.getCategory() != FeedbackCategoryEnum.BUG) {
            throw new IllegalArgumentException("只有 Bug 问题可以关联禅道 Bug");
        }
        String normalizedIssueKey = normalizeZenTaoIssueKey(issueKey);
        zenTaoService.validateBugLink(issue, normalizedIssueKey);
        issue.setRelatedIssue(normalizedIssueKey);
        issueRepo.save(issue);

        IssueTimeline timeline = IssueTimeline.builder()
                .issueId(issueId)
                .eventType("issue_linked")
                .detail("关联禅道 Bug: " + normalizedIssueKey)
                .build();
        timelineRepo.save(timeline);
    }

    private IssueListItemVO toListItemVO(FeedbackIssue issue) {
        return IssueListItemVO.builder()
                .id(issue.getId())
                .title(issue.getTitle())
                .category(issue.getCategory() != null ? issue.getCategory().name() : null)
                .categoryLabel(issue.getCategory() != null ? issue.getCategory().getLabel() : "")
                .module(issue.getModule())
                .severity(displaySeverity(issue.getCategory(), issue.getSeverity()))
                .priority(displayPriority(issue.getCategory(), issue.getPriority()))
                .status(issue.getStatus() != null ? issue.getStatus().getCode() : null)
                .statusLabel(issue.getStatus() != null ? issue.getStatus().getLabel() : "")
                .confirmed(isConfirmed(issue))
                .confirmedLabel(confirmedLabel(issue))
                .reportCount(issue.getReportCount())
                .firstReportAt(issue.getFirstReportAt())
                .latestReportAt(issue.getLatestReportAt())
                .relatedIssue(issue.getRelatedIssue())
                .build();
    }

    private FeedbackCategoryEnum parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String value = category.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "BUG", "缺陷", "故障", "问题" -> FeedbackCategoryEnum.BUG;
            case "PRAISE", "好评", "表扬" -> FeedbackCategoryEnum.PRAISE;
            case "SUGGESTION", "需求", "建议", "优化", "改进" -> FeedbackCategoryEnum.SUGGESTION;
            default -> null;
        };
    }

    private void validateStatusUpdate(FeedbackIssue issue, IssueStatusEnum newStatus) {
        if (newStatus == null) {
            throw new IllegalArgumentException("状态不能为空");
        }
        if (newStatus == IssueStatusEnum.MERGED) {
            throw new IllegalArgumentException("该状态为系统内部状态，不能手动设置");
        }
        if (issue.getCategory() == FeedbackCategoryEnum.PRAISE) {
            throw new IllegalArgumentException("好评记录不允许更新研发处理状态");
        }
        if (issue.getCategory() == FeedbackCategoryEnum.SUGGESTION) {
            if (!newStatus.isSuggestionWorkflowStatus()) {
                throw new IllegalArgumentException("建议只能使用建议池状态");
            }
            return;
        }
        if (!newStatus.isBugWorkflowStatus()) {
            throw new IllegalArgumentException("Bug 只能使用 Bug 处理状态");
        }
    }

    private String normalizeZenTaoIssueKey(String issueKey) {
        if (issueKey == null || issueKey.isBlank()) {
            throw new IllegalArgumentException("禅道 Bug 编号不能为空");
        }
        String trimmed = issueKey.trim().toUpperCase(Locale.ROOT);
        if (trimmed.matches("\\d+")) {
            return "ZT-BUG-" + trimmed;
        }
        if (trimmed.matches("ZT-BUG-\\d+")) {
            return trimmed;
        }
        throw new IllegalArgumentException("禅道 Bug 编号格式应为 ZT-BUG-123 或数字 ID");
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.UnicodeScript.of(value.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean isConfirmed(FeedbackIssue issue) {
        return issue != null && Boolean.TRUE.equals(issue.getConfirmed());
    }

    private String confirmedLabel(FeedbackIssue issue) {
        return isConfirmed(issue) ? "已确认" : "未确认";
    }

    private String displaySeverity(FeedbackCategoryEnum category, String severity) {
        return category == FeedbackCategoryEnum.BUG ? severity : null;
    }

    private String displayPriority(FeedbackCategoryEnum category, String priority) {
        return category == FeedbackCategoryEnum.BUG ? priority : null;
    }

    private String triageSourceLabel(String triageSource) {
        if (triageSource == null || triageSource.isBlank()) {
            return "";
        }
        return switch (triageSource.trim().toUpperCase(Locale.ROOT)) {
            case "AI_INITIAL" -> "AI 初始判断";
            case "SYSTEM_MANUAL" -> "人工修改";
            case "ZENTAO_WEBHOOK" -> "禅道回流";
            case "SYSTEM_DEFAULT" -> "系统默认";
            default -> triageSource;
        };
    }

    private String triageReasonDisplay(FeedbackIssue issue) {
        if (issue == null || issue.getCategory() != FeedbackCategoryEnum.BUG) {
            return "";
        }
        String reason = value(issue.getTriageReason()).trim();
        if (shouldDisplayOriginalTriageReason(reason)) {
            return reason;
        }
        String levelText = defaultIfBlank(displaySeverity(issue.getCategory(), issue.getSeverity()), "-")
                + "/" + defaultIfBlank(displayPriority(issue.getCategory(), issue.getPriority()), "-");
        String source = issue.getTriageSource() == null ? "" : issue.getTriageSource().trim().toUpperCase(Locale.ROOT);
        return switch (source) {
            case "AI_INITIAL" -> "AI 初始判断建议按 " + levelText + " 处理，历史定级理由为英文，页面不展示原文。";
            case "SYSTEM_MANUAL" -> "人工已将当前等级调整为 " + levelText + "。";
            case "ZENTAO_WEBHOOK" -> "已根据禅道回流同步当前等级为 " + levelText + "。";
            case "SYSTEM_DEFAULT" -> "系统使用默认等级 " + levelText + "。";
            default -> reason;
        };
    }

    private boolean shouldDisplayOriginalTriageReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        int chineseCount = countChineseCharacters(reason);
        int latinCount = countLatinLetters(reason);
        if (chineseCount == 0) {
            return false;
        }
        return latinCount <= Math.max(8, chineseCount / 2);
    }

    private int countChineseCharacters(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current >= 0x4E00 && current <= 0x9FFF) {
                count++;
            }
        }
        return count;
    }

    private int countLatinLetters(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if ((current >= 'a' && current <= 'z') || (current >= 'A' && current <= 'Z')) {
                count++;
            }
        }
        return count;
    }

    private String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            throw new IllegalArgumentException("严重度不能为空");
        }
        return switch (severity.trim().toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "1" -> "CRITICAL";
            case "HIGH", "2" -> "HIGH";
            case "MEDIUM", "3" -> "MEDIUM";
            case "LOW", "4" -> "LOW";
            default -> throw new IllegalArgumentException("不支持的严重度: " + severity);
        };
    }

    private String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            throw new IllegalArgumentException("优先级不能为空");
        }
        return switch (priority.trim().toUpperCase(Locale.ROOT)) {
            case "P1", "1" -> "P1";
            case "P2", "2" -> "P2";
            case "P3", "3" -> "P3";
            case "P4", "4" -> "P4";
            default -> throw new IllegalArgumentException("不支持的优先级: " + priority);
        };
    }

    private List<Map<String, Object>> parseSuspectedDuplicates(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse suspected duplicates", e);
            return List.of();
        }
    }

    private void mergeAffectVersions(FeedbackIssue target, String sourceVersions) {
        if (sourceVersions == null || sourceVersions.isBlank()) {
            return;
        }
        List<String> merged = new ArrayList<>();
        addVersions(merged, target.getAffectVersions());
        addVersions(merged, sourceVersions);
        target.setAffectVersions(String.join(",", merged));
    }

    private void recomputeIssueStats(FeedbackIssue issue) {
        List<FeedbackClaim> claims = claimRepo.findByIssueId(issue.getId());
        if (claims.isEmpty()) {
            issue.setReportCount(0);
            issue.setSuspectedDuplicates(null);
            return;
        }
        issue.setReportCount((int) claims.stream().map(FeedbackClaim::getRawId).distinct().count());
        claims.stream()
                .map(FeedbackClaim::getCreatedAt)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .ifPresent(issue::setFirstReportAt);
        claims.stream()
                .map(FeedbackClaim::getCreatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .ifPresent(issue::setLatestReportAt);
        claims.stream()
                .map(FeedbackClaim::getSummary)
                .filter(summary -> summary != null && !summary.isBlank())
                .max((left, right) -> Integer.compare(left.length(), right.length()))
                .ifPresent(summary -> {
                    issue.setAiSummary(summary);
                    issue.setTypicalContent(summary);
                });
    }

    private void appendZenTaoReassignComment(FeedbackIssue issue, String comment) {
        if (issue.getCategory() != FeedbackCategoryEnum.BUG
                || issue.getRelatedIssue() == null || issue.getRelatedIssue().isBlank()) {
            return;
        }
        boolean appended = zenTaoService.syncBugUpdate(issue, comment);
        timelineRepo.save(IssueTimeline.builder()
                .issueId(issue.getId())
                .eventType(appended ? "zentao_comment_appended" : "zentao_comment_skipped")
                .detail(appended ? "改归属后追加禅道备注：" + issue.getRelatedIssue()
                        : "改归属后禅道备注未追加：" + issue.getRelatedIssue())
                .build());
    }

    private void addVersions(List<String> target, String versions) {
        if (versions == null || versions.isBlank()) {
            return;
        }
        for (String version : versions.split(",")) {
            String item = version.trim();
            if (!item.isBlank() && !target.contains(item)) {
                target.add(item);
            }
        }
    }

    private void syncIssueDocument(FeedbackIssue issue) {
        try {
            if (issue.getStatus() != null && issue.getStatus().isArchivedStatus()) {
                issueEsRepo.deleteByIssueId(issue.getId());
                return;
            }
            if (issue.getEmbeddingVector() == null || issue.getEmbeddingVector().isBlank()) {
                return;
            }
            List<Double> vec = objectMapper.readValue(issue.getEmbeddingVector(), new TypeReference<List<Double>>() {});
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

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value(value);
        }
        return value.substring(0, maxLength) + "...";
    }
}
