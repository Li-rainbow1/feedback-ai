package com.feedback.analyzer.controller;

import com.feedback.analyzer.entity.ChannelConfig;
import com.feedback.analyzer.entity.FeedbackAnalyzed;
import com.feedback.analyzer.entity.FeedbackClaim;
import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.entity.Product;
import com.feedback.analyzer.model.ApiResult;
import com.feedback.analyzer.model.dto.FeedbackQueryRequest;
import com.feedback.analyzer.model.dto.FeedbackSubmitRequest;
import com.feedback.analyzer.model.dto.ManualFeedbackSubmitRequest;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.FeedbackClaimStatusEnum;
import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import com.feedback.analyzer.repository.ChannelConfigRepository;
import com.feedback.analyzer.repository.FeedbackClaimRepository;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import com.feedback.analyzer.service.ExcelImportService;
import com.feedback.analyzer.service.FeedbackService;
import com.feedback.analyzer.service.PraiseAggregationService;
import com.feedback.analyzer.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/feedbacks")
@RequiredArgsConstructor
@Validated
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final ProductService productService;
    private final ExcelImportService excelImportService;
    private final PraiseAggregationService praiseAggregationService;
    private final FeedbackClaimRepository claimRepo;
    private final FeedbackRawRepository rawRepo;
    private final ChannelConfigRepository channelConfigRepo;

    @PostMapping("/webhook")
    public ApiResult<Map<String, String>> webhook(@Valid @RequestBody FeedbackSubmitRequest request) {
        return ApiResult.success(submitWebhook(request, false));
    }

    @PostMapping("/webhook/sync")
    public ApiResult<Map<String, String>> webhookSync(@Valid @RequestBody FeedbackSubmitRequest request) {
        return ApiResult.success(submitWebhook(request, true));
    }

    @PostMapping("/webhook/batch")
    public ApiResult<List<Map<String, String>>> webhookBatch(@Valid @RequestBody List<@Valid FeedbackSubmitRequest> requests) {
        validateBatch(requests);
        List<Map<String, String>> results = new ArrayList<>();
        for (FeedbackSubmitRequest request : requests) {
            results.add(submitWebhook(request, false));
        }
        return ApiResult.success(results);
    }

    @PostMapping("/webhook/batch-sync")
    public ApiResult<List<Map<String, String>>> webhookBatchSync(@Valid @RequestBody List<@Valid FeedbackSubmitRequest> requests) {
        validateBatch(requests);
        List<Map<String, String>> results = new ArrayList<>();
        for (FeedbackSubmitRequest request : requests) {
            results.add(submitWebhook(request, true));
        }
        return ApiResult.success(results);
    }

    @PostMapping("/manual")
    public ApiResult<Map<String, String>> manualSubmit(@Valid @RequestBody ManualFeedbackSubmitRequest request) {
        productService.getById(request.getProductId());
        FeedbackRaw raw = feedbackService.submitSync(request.getProductId(), request.toSubmitRequest());
        return ApiResult.success(Map.of("rawId", raw.getId(), "status", raw.getStatus().getCode()));
    }

    @GetMapping("/status/{rawId}")
    public ApiResult<FeedbackRaw> getStatus(@PathVariable String rawId) {
        FeedbackRaw raw = feedbackService.getRawStatus(rawId);
        if (raw == null) {
            return ApiResult.error(404, "反馈不存在");
        }
        return ApiResult.success(raw);
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> getDetail(@PathVariable String id) {
        FeedbackAnalyzed analyzed = feedbackService.getAnalyzed(id);
        if (analyzed == null) {
            return ApiResult.error(404, "分析结果不存在");
        }
        FeedbackRaw raw = feedbackService.getRawStatus(analyzed.getRawId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", analyzed.getId());
        detail.put("rawId", analyzed.getRawId());
        detail.put("productId", analyzed.getProductId());
        detail.put("issueId", analyzed.getIssueId());
        detail.put("category", analyzed.getCategory());
        detail.put("module", analyzed.getModule());
        detail.put("keywords", analyzed.getKeywords());
        detail.put("summary", analyzed.getSummary());
        detail.put("analyzedAt", analyzed.getAnalyzedAt());
        if (raw != null) {
            detail.put("rawContent", raw.getRawContent());
            detail.put("channel", raw.getChannel());
            detail.put("sourceType", raw.getSourceType());
            detail.put("externalReviewId", raw.getExternalReviewId());
            detail.put("sourceMetadata", raw.getSourceMetadata());
            detail.put("feedbackTime", raw.getFeedbackTime());
        }
        return ApiResult.success(detail);
    }

    @PostMapping("/search")
    public ApiResult<Page<FeedbackAnalyzed>> search(@RequestBody FeedbackQueryRequest request) {
        return ApiResult.success(feedbackService.search(request));
    }

    @GetMapping("/raw-search")
    public ApiResult<Page<Map<String, Object>>> rawSearch(
            @RequestParam Long productId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FeedbackRaw> rawPage = rawRepo.searchRawFeedbacks(
                productId,
                blankToNull(keyword),
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<String> rawIds = rawPage.getContent().stream().map(FeedbackRaw::getId).toList();
        Map<String, List<FeedbackClaim>> claimsByRawId = rawIds.isEmpty()
                ? Map.of()
                : claimRepo.findByRawIdInOrderByRawIdAscClaimIndexAsc(rawIds).stream()
                .collect(Collectors.groupingBy(FeedbackClaim::getRawId, LinkedHashMap::new, Collectors.toList()));
        return ApiResult.success(rawPage.map(raw -> rawFeedbackRecord(raw, claimsByRawId.getOrDefault(raw.getId(), List.of()))));
    }

    @GetMapping("/praises")
    public ApiResult<Page<Map<String, Object>>> praiseRecords(
            @RequestParam Long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDateTime rangeStart = start != null ? start : LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime rangeEnd = end != null ? end : LocalDateTime.now();
        Page<FeedbackClaim> claims = claimRepo
                .findByProductIdAndCategoryAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                        productId,
                        FeedbackCategoryEnum.PRAISE,
                        FeedbackClaimStatusEnum.RECORDED,
                        rangeStart,
                        rangeEnd,
                        PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ApiResult.success(claims.map(this::praiseRecord));
    }

    @GetMapping("/praises/groups")
    public ApiResult<Page<Map<String, Object>>> praiseGroups(
            @RequestParam Long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDateTime rangeStart = start != null ? start : LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime rangeEnd = end != null ? end : LocalDateTime.now();
        List<Map<String, Object>> groups = praiseAggregationService.buildGroups(productId, rangeStart, rangeEnd, 0);
        int from = Math.min(Math.max(page - 1, 0) * size, groups.size());
        int to = Math.min(from + size, groups.size());
        return ApiResult.success(new PageImpl<>(
                groups.subList(from, to), PageRequest.of(Math.max(page - 1, 0), size), groups.size()));
    }

    @GetMapping("/praises/group-claims")
    public ApiResult<List<Map<String, Object>>> praiseGroupClaims(
            @RequestParam Long productId,
            @RequestParam String groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        LocalDateTime rangeStart = start != null ? start : LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime rangeEnd = end != null ? end : LocalDateTime.now();
        List<FeedbackClaim> claims = praiseAggregationService.getGroupClaims(productId, rangeStart, rangeEnd, groupId);
        Set<String> rawIds = claims.stream().map(FeedbackClaim::getRawId).collect(Collectors.toSet());
        Map<String, FeedbackRaw> rawMap = rawRepo.findAllById(rawIds).stream()
                .collect(Collectors.toMap(FeedbackRaw::getId, raw -> raw));
        return ApiResult.success(claims.stream().map(claim -> praiseRecord(claim, rawMap.get(claim.getRawId()))).toList());
    }

    @PostMapping("/import/excel")
    public ApiResult<Map<String, Object>> importExcel(
            @RequestParam Long productId,
            @RequestParam String channel,
            @RequestParam("file") MultipartFile file) {
        int count = excelImportService.importFromExcel(productId, channel, file);
        return ApiResult.success(Map.of("imported", count));
    }

    @GetMapping("/by-issue/{issueId}")
    public ApiResult<Page<FeedbackAnalyzed>> getByIssueId(
            @PathVariable String issueId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.success(feedbackService.getByIssueId(issueId, page, size));
    }

    private Map<String, String> submitWebhook(FeedbackSubmitRequest request, boolean sync) {
        Product product = validateWebhookRequest(request);
        FeedbackRaw raw = sync
                ? feedbackService.submitSync(product.getId(), request)
                : feedbackService.submit(product.getId(), request);
        return Map.of("rawId", raw.getId(), "status", raw.getStatus().getCode());
    }

    private Product validateWebhookRequest(FeedbackSubmitRequest request) {
        Product product = productService.getByWebhookToken(request.getWebhookToken());
        String sourceKey = blankToNull(request.getSourceKey());
        if (sourceKey == null) {
            throw new RuntimeException("sourceKey 不能为空");
        }
        ChannelConfig channel = channelConfigRepo
                .findByProductIdAndSourceKeyAndSourceTypeAndEnabledTrue(product.getId(), sourceKey, "webhook")
                .orElseThrow(() -> new RuntimeException("sourceKey 无效，或对应 Webhook 入口未启用"));
        if (!Boolean.TRUE.equals(channel.getEnabled())) {
            throw new RuntimeException("当前 Webhook 入口已停用");
        }
        request.setSourceKey(sourceKey);
        request.setChannel(blankToNull(request.getChannel()));
        if (request.getChannel() == null) {
            throw new RuntimeException("channel 不能为空");
        }
        return product;
    }

    private void validateBatch(List<FeedbackSubmitRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("批量反馈不能为空");
        }
        if (requests.size() > 100) {
            throw new IllegalArgumentException("批量提交最多支持 100 条");
        }
    }

    private Map<String, Object> rawFeedbackRecord(FeedbackRaw raw, List<FeedbackClaim> claims) {
        List<FeedbackClaim> sortedClaims = claims.stream()
                .sorted(Comparator.comparing(FeedbackClaim::getClaimIndex, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        Map<String, String> displayStatus = resolveDisplayStatus(raw, sortedClaims);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", raw.getId());
        item.put("productId", raw.getProductId());
        item.put("rawContent", raw.getRawContent());
        item.put("aiSummary", buildRawSummary(sortedClaims));
        item.put("claimCount", sortedClaims.size());
        item.put("channel", raw.getChannel());
        item.put("sourceType", raw.getSourceType());
        item.put("status", raw.getStatus() != null ? raw.getStatus().getCode() : "");
        item.put("statusLabel", rawStatusLabel(raw.getStatus()));
        item.put("displayStatus", displayStatus.get("code"));
        item.put("displayStatusLabel", displayStatus.get("label"));
        item.put("displayReason", displayStatus.get("reason"));
        item.put("userName", raw.getUserName());
        item.put("appVersion", raw.getAppVersion());
        item.put("deviceInfo", raw.getDeviceInfo());
        item.put("feedbackTime", raw.getFeedbackTime());
        item.put("createdAt", raw.getCreatedAt());
        item.put("claims", sortedClaims.stream().map(this::claimRecord).toList());
        return item;
    }

    private Map<String, Object> claimRecord(FeedbackClaim claim) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", claim.getId());
        item.put("analyzedId", claim.getAnalyzedId());
        item.put("claimIndex", claim.getClaimIndex());
        item.put("summary", claim.getSummary());
        item.put("content", claim.getContent());
        item.put("category", claim.getCategory());
        item.put("module", claim.getModule());
        item.put("keywords", claim.getKeywords());
        item.put("status", claim.getStatus() != null ? claim.getStatus().name() : "");
        item.put("statusLabel", claimStatusLabel(claim.getStatus()));
        item.put("decisionAction", claim.getDecisionAction());
        item.put("decisionReason", claim.getDecisionReason());
        item.put("decisionConfidence", claim.getDecisionConfidence());
        item.put("issueId", claim.getIssueId());
        item.put("createdAt", claim.getCreatedAt());
        return item;
    }

    private Map<String, String> resolveDisplayStatus(FeedbackRaw raw, List<FeedbackClaim> claims) {
        String reason = claims.stream()
                .map(FeedbackClaim::getDecisionReason)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
        if (raw.getStatus() == FeedbackStatusEnum.LOW_QUALITY) {
            return Map.of(
                    "code", "low_quality",
                    "label", "低质量",
                    "reason", reason.isBlank() ? "反馈信息不足，未进入有效分析" : reason);
        }
        if (!claims.isEmpty() && claims.stream().allMatch(claim -> claim.getStatus() == FeedbackClaimStatusEnum.IGNORED)) {
            return Map.of(
                    "code", "ignored",
                    "label", "已忽略",
                    "reason", reason.isBlank() ? "该反馈未形成有效好评或问题，不进入后续统计" : reason);
        }
        return Map.of(
                "code", raw.getStatus() != null ? raw.getStatus().getCode() : "",
                "label", rawStatusLabel(raw.getStatus()),
                "reason", reason);
    }

    private String buildRawSummary(List<FeedbackClaim> claims) {
        return claims.stream()
                .map(claim -> firstNonBlank(claim.getSummary(), claim.getContent()))
                .filter(summary -> summary != null && !summary.isBlank())
                .distinct()
                .limit(3)
                .collect(Collectors.joining("；"));
    }

    private String rawStatusLabel(FeedbackStatusEnum status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case RAW -> "待处理";
            case CLEANED -> "已清洗";
            case SKIPPED -> "已去重";
            case ANALYZING -> "分析中";
            case ANALYZED -> "已分析";
            case LOW_QUALITY -> "低质量";
        };
    }

    private String claimStatusLabel(FeedbackClaimStatusEnum status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case PENDING -> "待处理";
            case MERGED -> "已归并";
            case CREATED -> "已建问题";
            case RECORDED -> "已记录";
            case IGNORED -> "已忽略";
        };
    }

    private Map<String, Object> praiseRecord(FeedbackClaim claim) {
        return praiseRecord(claim, null);
    }

    private Map<String, Object> praiseRecord(FeedbackClaim claim, FeedbackRaw raw) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", claim.getId());
        item.put("rawId", claim.getRawId());
        item.put("module", claim.getModule());
        item.put("summary", claim.getSummary());
        item.put("content", claim.getContent());
        item.put("keywords", claim.getKeywords());
        item.put("status", "recorded");
        item.put("statusLabel", "已记录");
        item.put("createdAt", claim.getCreatedAt());
        if (raw != null) {
            item.put("rawContent", raw.getRawContent());
            item.put("feedbackTime", raw.getFeedbackTime());
            item.put("channel", raw.getChannel());
            item.put("sourceType", raw.getSourceType());
        }
        return item;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return "";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
