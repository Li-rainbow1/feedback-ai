package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackAnalyzed;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.entity.Product;
import com.feedback.analyzer.model.dto.ZenTaoBugSnapshot;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.repository.FeedbackAnalyzedRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import com.feedback.analyzer.repository.ProductRepository;
import com.feedback.analyzer.service.IssueService;
import com.feedback.analyzer.service.ZenTaoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ZenTaoServiceImpl implements ZenTaoService {

    private final IssueService issueService;
    private final ProductRepository productRepo;
    private final FeedbackIssueRepository issueRepo;
    private final FeedbackAnalyzedRepository analyzedRepo;
    private final FeedbackRawRepository rawRepo;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final RestClient restClient;
    private final String account;
    private final String password;
    private final String defaultOpenedBuild;
    private final String entryCode;
    private final String entryToken;
    private final AtomicLong entryTokenTime = new AtomicLong();

    private String cachedToken;

    public ZenTaoServiceImpl(@Lazy IssueService issueService,
                              ProductRepository productRepo,
                              FeedbackIssueRepository issueRepo,
                              FeedbackAnalyzedRepository analyzedRepo,
                              FeedbackRawRepository rawRepo,
                              ObjectMapper objectMapper,
                              @Value("${zentao.enabled:false}") boolean enabled,
                              @Value("${zentao.url:}") String url,
                              @Value("${zentao.account:admin}") String account,
                              @Value("${zentao.password:}") String password,
                              @Value("${zentao.default-opened-build:trunk}") String defaultOpenedBuild,
                              @Value("${zentao.entry-code:}") String entryCode,
                              @Value("${zentao.entry-token:}") String entryToken,
                              @Value("${zentao.connect-timeout-ms:3000}") int connectTimeoutMs,
                              @Value("${zentao.read-timeout-ms:8000}") int readTimeoutMs) {
        this.issueService = issueService;
        this.productRepo = productRepo;
        this.issueRepo = issueRepo;
        this.analyzedRepo = analyzedRepo;
        this.rawRepo = rawRepo;
        this.objectMapper = objectMapper;
        this.enabled = enabled && !url.isBlank();
        this.account = account;
        this.password = password;
        this.defaultOpenedBuild = defaultOpenedBuild;
        this.entryCode = entryCode == null ? "" : entryCode.trim();
        this.entryToken = entryToken == null ? "" : entryToken.trim();
        this.restClient = this.enabled ? RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs))
                .build() : null;
        log.info("ZenTao init: enabled={} url={}", this.enabled, url);
    }

    private SimpleClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    @Override
    public String createBug(FeedbackIssue issue) {
        return createBug(issue, null, null);
    }

    @Override
    public String createBug(FeedbackIssue issue, String title, String description) {
        if (!enabled) return null;

        FeedbackIssue persistedIssue = issueRepo.findById(issue.getId()).orElse(issue);
        if (persistedIssue.getRelatedIssue() != null && !persistedIssue.getRelatedIssue().isBlank()) {
            log.info("ZenTao bug already linked: {} for issue: {}",
                    persistedIssue.getRelatedIssue(), persistedIssue.getId());
            return persistedIssue.getRelatedIssue();
        }
        FeedbackIssue latestIssue = issue;

        Product product = productRepo.findById(latestIssue.getProductId()).orElse(null);
        if (product == null || product.getZentaoProductId() == null) {
            log.warn("No ZenTao product mapping for product: {}", latestIssue.getProductId());
            return null;
        }
        int ztProductId = product.getZentaoProductId();

        try {
            String token = getToken();
            if (token == null) return null;

            int severity = toZenTaoSeverity(latestIssue.getSeverity());
            Map<String, Object> body = new HashMap<>();
            String bugTitle = title != null && !title.isBlank() ? title : latestIssue.getTitle();
            body.put("title", truncate("[用户反馈] " + bugTitle, 180));
            // 后端按 Issue 严重度写入禅道严重程度和优先级；指派人、抄送等协作字段交给禅道内人工处理。
            body.put("severity", severity);
            body.put("type", "codeerror");
            body.put("pri", toZenTaoPriority(latestIssue.getPriority()));
            body.put("steps", buildSteps(latestIssue));
            body.put("openedBuild", buildOpenedBuilds(latestIssue));
            body.put("keywords", buildKeywords(latestIssue));
            Integer moduleId = resolveModuleId(ztProductId, latestIssue.getModule(), token);
            if (moduleId != null && moduleId > 0) {
                body.put("module", moduleId);
            }

            String resp = restClient.post()
                .uri("/api.php/v1/products/{id}/bugs", ztProductId)
                .header("Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

            log.info("ZenTao response: {}", resp);
            JsonNode node = objectMapper.readTree(resp);
            JsonNode idNode = node.has("id") ? node.get("id") : node.path("data").path("id");
            if (!idNode.isMissingNode() && !idNode.isNull()) {
                String bugId = String.valueOf(idNode.asLong());
                String issueKey = "ZT-BUG-" + bugId;
                issueService.linkIssue(issue.getId(), issueKey);
                addBugComment(issueKey, buildCreateMetadataComment(latestIssue, product, description));
                log.info("ZenTao bug created: {} for issue: {}", bugId, issue.getId());
                return issueKey;
            }
            log.warn("ZenTao unexpected response: {}", resp);
        } catch (Exception e) {
            log.error("ZenTao failed for issue {}: {}", issue.getId(), e.getMessage());
        }
        return null;
    }

    @Override
    public boolean syncBugUpdate(FeedbackIssue issue) {
        return syncBugUpdate(issue, null);
    }

    @Override
    public boolean syncBugUpdate(FeedbackIssue issue, String comment) {
        if (!enabled || issue == null || issue.getRelatedIssue() == null || issue.getRelatedIssue().isBlank()) {
            return false;
        }
        return addBugComment(issue.getRelatedIssue(), buildUpdateComment(issue, comment));
    }

    @Override
    public boolean updateBugTriage(FeedbackIssue issue) {
        if (!enabled || issue == null || issue.getRelatedIssue() == null || issue.getRelatedIssue().isBlank()) {
            return false;
        }
        Long bugId = parseBugId(issue.getRelatedIssue());
        if (bugId == null) {
            return false;
        }
        try {
            String token = getToken();
            if (token == null) {
                return false;
            }
            Map<String, Object> body = new HashMap<>();
            body.put("severity", toZenTaoSeverity(issue.getSeverity()));
            body.put("pri", toZenTaoPriority(issue.getPriority()));
            restClient.put()
                    .uri("/api.php/v1/bugs/{id}", bugId)
                    .header("Token", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            addBugComment(issue.getRelatedIssue(), buildTriageUpdateComment(issue));
            return true;
        } catch (Exception e) {
            log.warn("ZenTao triage update failed for issue {}: {}", issue.getId(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean updateBugStatus(FeedbackIssue issue, IssueStatusEnum status) {
        if (!enabled || issue == null || issue.getRelatedIssue() == null || issue.getRelatedIssue().isBlank()) {
            return false;
        }
        Long bugId = parseBugId(issue.getRelatedIssue());
        if (bugId == null || status == null) {
            return false;
        }
        try {
            String token = getToken();
            if (token == null) {
                return false;
            }
            JsonNode bug = fetchBugNode(bugId, token);
            String currentStatus = firstTextDeep(bug, "status", "statusName");
            if (status == IssueStatusEnum.OPEN || status == IssueStatusEnum.FIXING) {
                if (isZenTaoStatus(currentStatus, "active")) {
                    return true;
                }
                return activateBug(bugId, token, issue, status);
            }
            if (status == IssueStatusEnum.RESOLVED) {
                if (isZenTaoStatus(currentStatus, "resolved")) {
                    return true;
                }
                return resolveBug(bugId, token, issue);
            }
            if (status == IssueStatusEnum.CLOSED) {
                if (isZenTaoStatus(currentStatus, "closed")) {
                    return true;
                }
                return closeBug(bugId, token, issue);
            }
            return false;
        } catch (Exception e) {
            log.warn("ZenTao status update failed for issue {}: {}", issue.getId(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean confirmBug(FeedbackIssue issue) {
        if (!enabled || issue == null || issue.getRelatedIssue() == null || issue.getRelatedIssue().isBlank()) {
            return false;
        }
        Long bugId = parseBugId(issue.getRelatedIssue());
        if (bugId == null) {
            return false;
        }
        try {
            String token = getToken();
            if (token == null) {
                return false;
            }
            JsonNode bug = fetchBugNode(bugId, token);
            Boolean confirmed = fromZenTaoConfirmed(firstTextDeep(bug, "confirmed", "confirmedName"));
            if (Boolean.TRUE.equals(confirmed)) {
                return true;
            }
            Map<String, Object> body = new HashMap<>();
            body.put("comment", "智能反馈系统确认该 Bug 有效");
            restClient.post()
                    .uri("/api.php/v1/bugs/{id}/confirm", bugId)
                    .header("Token", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return true;
        } catch (Exception e) {
            log.warn("ZenTao confirm failed for issue {}: {}", issue.getId(), e.getMessage());
            return false;
        }
    }

    @Override
    public void validateBugLink(FeedbackIssue issue, String issueKey) {
        if (issue == null) {
            throw new IllegalArgumentException("Issue 不存在");
        }
        if (!enabled) {
            throw new IllegalStateException("禅道未启用，不能校验并绑定禅道 Bug");
        }
        Long bugId = parseBugId(issueKey);
        if (bugId == null) {
            throw new IllegalArgumentException("禅道 Bug 编号格式不正确");
        }
        Product product = productRepo.findById(issue.getProductId()).orElse(null);
        if (product == null || product.getZentaoProductId() == null) {
            throw new IllegalArgumentException("当前产品未配置禅道产品映射");
        }
        try {
            String token = getToken();
            if (token == null) {
                throw new IllegalStateException("无法获取禅道 Token");
            }
            JsonNode bug = fetchBugNode(bugId, token);
            if (bug == null || bug.isMissingNode() || bug.isNull()) {
                throw new IllegalArgumentException("禅道 Bug 不存在：" + issueKey);
            }
            String status = firstText(bug, "status", "statusName");
            if (isClosedOrDeleted(status)) {
                throw new IllegalArgumentException("禅道 Bug 已关闭或已删除，不能绑定：" + issueKey);
            }
            Integer bugProductId = extractProductId(bug);
            if (bugProductId != null && !bugProductId.equals(product.getZentaoProductId())) {
                throw new IllegalArgumentException("禅道 Bug 所属产品与当前产品不匹配");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("禅道 Bug 校验失败：" + e.getMessage(), e);
        }
    }

    @Override
    public ZenTaoBugSnapshot fetchBugSnapshot(String issueKey) {
        if (!enabled) {
            throw new IllegalStateException("禅道未启用，无法查询 Bug 详情");
        }
        Long bugId = parseBugId(issueKey);
        if (bugId == null) {
            throw new IllegalArgumentException("禅道 Bug 编号格式不正确：" + issueKey);
        }
        try {
            String token = getToken();
            if (token == null) {
                throw new IllegalStateException("无法获取禅道 Token");
            }
            JsonNode bug = fetchBugNode(bugId, token);
            if (bug == null || bug.isMissingNode() || bug.isNull()) {
                throw new IllegalArgumentException("禅道 Bug 不存在：" + issueKey);
            }
            return new ZenTaoBugSnapshot(
                    issueKey,
                    firstTextDeep(bug, "status", "statusName"),
                    fromZenTaoConfirmed(firstTextDeep(bug, "confirmed", "confirmedName")),
                    fromZenTaoSeverity(firstTextDeep(bug, "severity", "severityName")),
                    fromZenTaoPriority(firstTextDeep(bug, "pri", "priority", "priorityName"))
            );
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("禅道 Bug 查询失败：" + e.getMessage(), e);
        }
    }

    private String getToken() {
        if (cachedToken != null) return cachedToken;
        try {
            Map<String, String> body = Map.of("account", account, "password", password);
            String resp = restClient.post()
                .uri("/api.php/v1/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            JsonNode node = objectMapper.readTree(resp);
            cachedToken = node.get("token").asText();
            log.info("ZenTao token obtained");
            return cachedToken;
        } catch (Exception e) {
            log.error("ZenTao getToken failed: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode fetchBugNode(Long bugId, String token) throws Exception {
        String resp = restClient.get()
                .uri("/api.php/v1/bugs/{id}", bugId)
                .header("Token", token)
                .retrieve()
                .body(String.class);
        JsonNode root = objectMapper.readTree(resp);
        if (root.has("bug")) {
            return root.get("bug");
        }
        if (root.has("data")) {
            return root.get("data");
        }
        if (root.has("id")) {
            return root;
        }
        return null;
    }

    private Integer extractProductId(JsonNode bug) {
        if (bug == null || bug.isMissingNode() || bug.isNull()) {
            return null;
        }
        JsonNode productNode = bug.path("product");
        if (productNode.canConvertToInt()) {
            return productNode.asInt();
        }
        if (productNode.isObject()) {
            JsonNode id = productNode.path("id");
            if (id.canConvertToInt()) {
                return id.asInt();
            }
        }
        JsonNode productIdNode = bug.path("productID");
        if (productIdNode.canConvertToInt()) {
            return productIdNode.asInt();
        }
        productIdNode = bug.path("productId");
        if (productIdNode.canConvertToInt()) {
            return productIdNode.asInt();
        }
        return null;
    }

    private boolean isClosedOrDeleted(String status) {
        if (status == null) {
            return false;
        }
        String value = status.trim().toLowerCase();
        return value.equals("closed") || value.equals("deleted") || value.equals("已关闭") || value.equals("已删除");
    }

    private boolean activateBug(Long bugId, String token, FeedbackIssue issue, IssueStatusEnum status) {
        Map<String, Object> body = new HashMap<>();
        body.put("openedBuild", buildOpenedBuilds(issue));
        body.put("comment", status == IssueStatusEnum.FIXING
                ? "智能反馈系统状态更新：修复中"
                : "智能反馈系统状态更新：待处理");
        restClient.post()
                .uri("/api.php/v1/bugs/{id}/active", bugId)
                .header("Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return true;
    }

    private boolean resolveBug(Long bugId, String token, FeedbackIssue issue) {
        Map<String, Object> body = new HashMap<>();
        body.put("resolution", "fixed");
        body.put("resolvedBuild", firstOpenedBuild(issue));
        body.put("comment", "智能反馈系统状态更新：已解决");
        restClient.post()
                .uri("/api.php/v1/bugs/{id}/resolve", bugId)
                .header("Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return true;
    }

    private boolean closeBug(Long bugId, String token, FeedbackIssue issue) {
        Map<String, Object> body = new HashMap<>();
        body.put("comment", "智能反馈系统状态更新：已关闭");
        restClient.post()
                .uri("/api.php/v1/bugs/{id}/close", bugId)
                .header("Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return true;
    }

    private boolean isZenTaoStatus(String currentStatus, String expected) {
        if (currentStatus == null || currentStatus.isBlank()) {
            return false;
        }
        String value = currentStatus.trim().toLowerCase();
        return switch (expected) {
            case "active" -> value.equals("active") || value.equals("open") || value.equals("激活") || value.equals("未解决");
            case "resolved" -> value.equals("resolved") || value.equals("done") || value.equals("fixed") || value.equals("已解决");
            case "closed" -> value.equals("closed") || value.equals("已关闭");
            default -> false;
        };
    }

    private String buildSteps(FeedbackIssue issue) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p>[步骤] ")
                .append("暂未从用户反馈中提取到明确可复现步骤，请结合日志、监控和用户反馈样例补充。")
                .append("</p>");
        sb.append("<p>[结果] ")
                .append(esc(blankToDefault(issue.getAiSummary(), issue.getTitle())))
                .append("</p>");
        sb.append("<p>[期望] ")
                .append("对应业务流程应正常完成，用户不应因该问题受阻。")
                .append("</p>");
        return sb.toString();
    }

    private List<String> buildOpenedBuilds(FeedbackIssue issue) {
        Set<String> builds = new LinkedHashSet<>();
        addVersionTokens(builds, issue != null ? issue.getAffectVersions() : null);
        addVersionTokens(builds, defaultOpenedBuild);
        if (builds.isEmpty()) {
            builds.add("trunk");
        }
        return new ArrayList<>(builds).stream().limit(5).toList();
    }

    private String firstOpenedBuild(FeedbackIssue issue) {
        List<String> builds = buildOpenedBuilds(issue);
        return builds.isEmpty() ? "trunk" : builds.get(0);
    }

    private void addVersionTokens(Set<String> builds, String versions) {
        if (versions == null || versions.isBlank()) {
            return;
        }
        for (String token : versions.split("[,，;；、\\s]+")) {
            String value = token == null ? "" : token.trim();
            if (value.isBlank() || "-".equals(value) || "无".equals(value) || "未知".equals(value)) {
                continue;
            }
            builds.add(value);
        }
    }

    private Integer resolveModuleId(int productId, String moduleName, String token) {
        if (moduleName == null || moduleName.isBlank() || isGenericModule(moduleName)) {
            return null;
        }
        try {
            String resp = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api.php/v1/modules")
                            .queryParam("id", productId)
                            .queryParam("type", "bug")
                            .build())
                    .header("Token", token)
                    .retrieve()
                    .body(String.class);
            JsonNode modules = objectMapper.readTree(resp).path("modules");
            Integer matched = findModuleId(modules, moduleName.trim());
            if (matched == null) {
                log.info("ZenTao module not found, productId={}, module={}", productId, moduleName);
            }
            return matched;
        } catch (Exception e) {
            log.warn("ZenTao module resolve skipped, productId={}, module={}, error={}",
                    productId, moduleName, e.getMessage());
            return null;
        }
    }

    private Integer findModuleId(JsonNode node, String moduleName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                Integer result = findModuleId(item, moduleName);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }
        String name = firstText(node, "name", "title", "text", "label");
        if (name != null && moduleName.equals(name.trim())) {
            JsonNode idNode = node.path("id");
            if (idNode.canConvertToInt()) {
                return idNode.asInt();
            }
            String value = firstText(node, "value", "key");
            if (value != null) {
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        Integer fromChildren = findModuleId(node.path("children"), moduleName);
        if (fromChildren != null) {
            return fromChildren;
        }
        return findModuleId(node.path("items"), moduleName);
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
            if (value.isNumber()) {
                return value.asText();
            }
        }
        return null;
    }

    private String firstTextDeep(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
            if (value.isNumber()) {
                return value.asText();
            }
            if (value.isObject()) {
                String nested = firstText(value, "id", "value", "code", "name", "title", "label");
                if (nested != null && !nested.isBlank()) {
                    return nested;
                }
            }
        }
        return null;
    }

    private boolean isGenericModule(String module) {
        String value = module.trim();
        return Set.of("整体", "总体", "其他", "未知", "通用", "默认", "未分类", "无", "/").contains(value);
    }

    private List<FeedbackSample> loadSamples(String issueId, int limit) {
        List<FeedbackAnalyzed> analyzedList = analyzedRepo.findByIssueId(
                issueId, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "analyzedAt"))).getContent();
        List<String> rawIds = analyzedList.stream()
                .map(FeedbackAnalyzed::getRawId)
                .filter(rawId -> rawId != null && !rawId.isBlank())
                .toList();
        Map<String, FeedbackRaw> rawMap = rawRepo.findAllById(rawIds).stream()
                .collect(Collectors.toMap(FeedbackRaw::getId, raw -> raw));
        List<FeedbackSample> samples = new ArrayList<>();
        for (FeedbackAnalyzed analyzed : analyzedList) {
            FeedbackRaw raw = rawMap.get(analyzed.getRawId());
            samples.add(new FeedbackSample(
                    raw != null ? raw.getRawContent() : analyzed.getSummary(),
                    raw != null ? raw.getChannel() : null,
                    raw != null ? raw.getUserId() : null,
                    raw != null ? raw.getUserName() : null,
                    raw != null ? raw.getAppVersion() : null,
                    raw != null ? raw.getDeviceInfo() : null,
                    raw != null ? raw.getStar() : null,
                    raw != null && raw.getFeedbackTime() != null ? formatTime(raw.getFeedbackTime()) : null
            ));
        }
        return samples;
    }

    private boolean addBugComment(String relatedIssue, String content) {
        Long bugId = parseBugId(relatedIssue);
        if (bugId == null) {
            return false;
        }
        if (entryCode.isBlank() || entryToken.isBlank()) {
            log.info("ZenTao comment sync skipped for {}: entry code/token not configured", relatedIssue);
            return false;
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("comment", content);
            String time = nextEntryTokenTime();
            String token = md5(entryCode + entryToken + time);
            restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api.php")
                            .queryParam("m", "action")
                            .queryParam("f", "comment")
                            .queryParam("objectType", "bug")
                            .queryParam("objectID", bugId)
                            .queryParam("code", entryCode)
                            .queryParam("time", time)
                            .queryParam("token", token)
                            .build())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return true;
        } catch (Exception e) {
            log.warn("ZenTao comment sync skipped for {}: {}", relatedIssue, e.getMessage());
            return false;
        }
    }

    private String nextEntryTokenTime() {
        long now = System.currentTimeMillis() / 1000;
        long value = entryTokenTime.updateAndGet(previous -> Math.max(now, previous + 1));
        return String.valueOf(value);
    }

    private String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm unavailable", e);
        }
    }

    private String buildUpdateComment(FeedbackIssue issue, String aiComment) {
        StringBuilder sb = new StringBuilder();
        long recentCount = countRecentFeedbacks(issue.getId());
        sb.append("<p><strong>新增反馈：</strong>")
                .append(esc(buildReadableFeedbackSummary(issue, aiComment)))
                .append("</p>");
        sb.append("<p><strong>当前热度：</strong>")
                .append("累计 ").append(issue.getReportCount() != null ? issue.getReportCount() : 0).append(" 条")
                .append("，近 24 小时新增 ").append(recentCount).append(" 条")
                .append("；影响版本：").append(esc(formatVersionsForComment(issue.getAffectVersions())))
                .append("；最近反馈：").append(formatTime(issue.getLatestReportAt()))
                .append("</p>");
        return sb.toString();
    }

    private String buildTriageUpdateComment(FeedbackIssue issue) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p><strong>系统定级更新：</strong>")
                .append("严重程度 ").append(esc(blankToDefault(issue.getSeverity(), "-")))
                .append("，优先级 ").append(esc(blankToDefault(issue.getPriority(), "-")))
                .append("</p>");
        if (issue.getTriageReason() != null && !issue.getTriageReason().isBlank()) {
            sb.append("<p><strong>原因：</strong>")
                    .append(esc(issue.getTriageReason()))
                    .append("</p>");
        }
        return sb.toString();
    }

    private String buildReadableFeedbackSummary(FeedbackIssue issue, String aiComment) {
        String summary = aiComment;
        if (summary != null) {
            summary = summary.replaceFirst("^新增用户反馈归并[:：\\s]*", "").trim();
        }
        if (summary == null || summary.isBlank()) {
            summary = blankToDefault(issue.getAiSummary(), issue.getTitle());
        }
        return blankToDefault(summary, "-");
    }

    private String formatVersionsForComment(String versions) {
        if (versions == null || versions.isBlank()) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        for (String token : versions.split("[,，；;、\\s]+")) {
            String value = token == null ? "" : token.trim();
            if (!value.isBlank() && !values.contains(value)) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return "-";
        }
        int shown = Math.min(values.size(), 3);
        String display = String.join("、", values.subList(0, shown));
        if (values.size() > shown) {
            display += " 等" + values.size() + " 个版本";
        }
        return display;
    }

    private long countRecentFeedbacks(String issueId) {
        if (issueId == null || issueId.isBlank()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        return analyzedRepo.countByIssueIdAndAnalyzedAtBetween(issueId, now.minusHours(24), now);
    }

    private String buildCreateMetadataComment(FeedbackIssue issue, Product product, String aiDescription) {
        List<FeedbackSample> samples = loadSamples(issue.getId(), 5);
        StringBuilder sb = new StringBuilder();
        sb.append("<p><strong>智能反馈系统记录：</strong></p>");
        sb.append("<ul>");
        sb.append("<li>来源：智能反馈分析系统自动创建</li>");
        sb.append("<li>产品：").append(esc(product.getName())).append("</li>");
        sb.append("<li>分类：").append(esc(issue.getCategory() != null ? issue.getCategory().getLabel() : "-")).append("</li>");
        sb.append("<li>模块：").append(esc(blankToDefault(issue.getModule(), "待分析"))).append("</li>");
        sb.append("<li>严重程度：").append(esc(blankToDefault(issue.getSeverity(), "-"))).append("</li>");
        sb.append("<li>优先级：").append(esc(blankToDefault(issue.getPriority(), "-"))).append("</li>");
        if (issue.getTriageReason() != null && !issue.getTriageReason().isBlank()) {
            sb.append("<li>初始定级理由：").append(esc(issue.getTriageReason())).append("</li>");
        }
        sb.append("<li>反馈次数：").append(issue.getReportCount() != null ? issue.getReportCount() : 0).append("</li>");
        sb.append("<li>影响版本：").append(esc(formatVersionsForComment(issue.getAffectVersions()))).append("</li>");
        sb.append("<li>首次出现：").append(formatTime(issue.getFirstReportAt())).append("</li>");
        sb.append("<li>最近出现：").append(formatTime(issue.getLatestReportAt())).append("</li>");
        sb.append("</ul>");
        sb.append("<p><strong>AI 摘要：</strong>")
                .append(esc(blankToDefault(issue.getAiSummary(), issue.getTitle())))
                .append("</p>");
        if (aiDescription != null && !aiDescription.isBlank()) {
            sb.append("<p><strong>AI 判断：</strong></p>")
                    .append(toHtmlParagraphs(aiDescription));
        }
        sb.append("<p><strong>建议处理：</strong>请研发确认可复现性、根因、修复版本和解决方案。</p>");
        appendFeedbackSamples(sb, samples);
        return sb.toString();
    }
    private void appendFeedbackSamples(StringBuilder sb, List<FeedbackSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        sb.append("<p><strong>用户反馈样例：</strong></p><ol>");
        for (FeedbackSample sample : samples) {
            sb.append("<li>")
                    .append(esc(sample.rawContent()))
                    .append("<br/>渠道：").append(esc(blankToDefault(sample.channel(), "-")))
                    .append("；用户：").append(esc(blankToDefault(sample.userName(), sample.userId())))
                    .append("；版本：").append(esc(blankToDefault(sample.appVersion(), "-")))
                    .append("；设备：").append(esc(blankToDefault(sample.deviceInfo(), "-")))
                    .append("；满意度评分：").append(sample.star() != null ? sample.star() : "-")
                    .append("；时间：").append(esc(blankToDefault(sample.feedbackTime(), "-")))
                    .append("</li>");
        }
        sb.append("</ol>");
    }

    private Long parseBugId(String relatedIssue) {
        try {
            return Long.parseLong(relatedIssue.replace("ZT-BUG-", "").trim());
        } catch (Exception e) {
            return null;
        }
    }

    private int toZenTaoSeverity(String severity) {
        if ("CRITICAL".equalsIgnoreCase(severity)) {
            return 1;
        }
        if ("HIGH".equalsIgnoreCase(severity)) {
            return 2;
        }
        return "MEDIUM".equalsIgnoreCase(severity) ? 3 : 4;
    }

    private int toZenTaoPriority(String priority) {
        if ("P1".equalsIgnoreCase(priority)) {
            return 1;
        }
        if ("P2".equalsIgnoreCase(priority)) {
            return 2;
        }
        return "P4".equalsIgnoreCase(priority) ? 4 : 3;
    }

    private String fromZenTaoSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return null;
        }
        return switch (severity.trim().toUpperCase()) {
            case "1", "CRITICAL", "严重", "致命" -> "CRITICAL";
            case "2", "HIGH", "高" -> "HIGH";
            case "3", "MEDIUM", "中" -> "MEDIUM";
            case "4", "LOW", "低" -> "LOW";
            default -> null;
        };
    }

    private String fromZenTaoPriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return null;
        }
        return switch (priority.trim().toUpperCase()) {
            case "1", "P1" -> "P1";
            case "2", "P2" -> "P2";
            case "3", "P3" -> "P3";
            case "4", "P4" -> "P4";
            default -> null;
        };
    }

    private Boolean fromZenTaoConfirmed(String confirmed) {
        if (confirmed == null || confirmed.isBlank()) {
            return null;
        }
        String value = confirmed.trim().toLowerCase();
        if (value.equals("1") || value.equals("true") || value.equals("yes")
                || value.equals("confirmed") || value.equals("已确认")) {
            return true;
        }
        if (value.equals("0") || value.equals("false") || value.equals("no")
                || value.equals("unconfirmed") || value.equals("未确认")) {
            return false;
        }
        return null;
    }

    private String buildKeywords(FeedbackIssue issue) {
        List<String> keywords = new ArrayList<>();
        keywords.add("用户反馈");
        keywords.add("自动创建");
        if (issue.getModule() != null && !issue.getModule().isBlank()) {
            keywords.add(issue.getModule());
        }
        if (issue.getCategory() != null) {
            keywords.add(issue.getCategory().name());
        }
        return String.join(",", keywords);
    }

    private String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String toHtmlParagraphs(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(text.split("\\R+"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(line -> "<p>" + esc(line) + "</p>")
                .collect(Collectors.joining());
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String formatTime(java.time.LocalDateTime time) {
        return time == null ? "-" : time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private record FeedbackSample(String rawContent, String channel, String userId, String userName,
                                  String appVersion, String deviceInfo, Integer star, String feedbackTime) {}
}
