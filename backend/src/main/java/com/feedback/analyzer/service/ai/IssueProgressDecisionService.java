package com.feedback.analyzer.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.AiRunLog;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.entity.IssueTimeline;
import com.feedback.analyzer.model.dto.AiIssueDecision;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.repository.FeedbackAnalyzedRepository;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.IssueTimelineRepository;
import com.feedback.analyzer.service.AiRateLimiter;
import com.feedback.analyzer.service.PromptTemplateService;
import com.feedback.analyzer.service.ZenTaoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class IssueProgressDecisionService {

    private static final String LOG_TYPE = "issue_progress";
    private static final int FEEDBACK_WINDOW_HOURS = 24;

    private final FeedbackAnalyzedRepository analyzedRepo;
    private final FeedbackIssueRepository issueRepo;
    private final IssueTimelineRepository timelineRepo;
    private final ZenTaoService zenTaoService;
    private final AiRunLogger runLogger;
    private final ObjectMapper objectMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final PromptTemplateService promptTemplateService;
    private final AiRateLimiter aiRateLimiter;

    public AiIssueDecision progress(FeedbackIssue issue) {
        List<String> thoughts = new ArrayList<>();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        AiRunLog run = runLogger.start(LOG_TYPE, "issue", issue.getId(), issue.getProductId(), Map.of(
                "issueId", issue.getId(),
                "title", value(issue.getTitle()),
                "category", issue.getCategory() != null ? issue.getCategory().name() : "",
                "severity", value(issue.getSeverity()),
                "priority", value(issue.getPriority()),
                "status", issue.getStatus() != null ? issue.getStatus().name() : "",
                "reportCount", issue.getReportCount() != null ? issue.getReportCount() : 0,
                "relatedIssue", value(issue.getRelatedIssue())
        ));

        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime windowStart = now.minusHours(FEEDBACK_WINDOW_HOURS);
            long recentCount = analyzedRepo.countByIssueIdAndAnalyzedAtBetween(issue.getId(), windowStart, now);
            List<IssueTimeline> timeline = timelineRepo.findByIssueIdOrderByCreatedAtDesc(issue.getId());

            toolCalls.add(Map.of(
                    "tool", "countRecentFeedbacks",
                    "windowHours", FEEDBACK_WINDOW_HOURS,
                    "recentCount", recentCount
            ));

            String plan = "读取问题现状，判断禅道动作；飞书只发周报，不发单个 Bug 告警。";
            thoughts.add(plan);
            toolCalls.add(Map.of("tool", "buildProgressPlan", "plan", plan));

            if (!isActive(issue)) {
                AiIssueDecision decision = AiIssueDecision.builder()
                        .action("NOOP")
                        .reason("问题已结束，无需继续推进")
                        .recentCount(recentCount)
                        .plan(plan)
                        .zentaoAction("NOOP")
                        .notificationSent(false)
                        .build();
                thoughts.add("问题状态已结束，跳过禅道和飞书处理。");
                runLogger.success(run, thoughts, toolCalls, decision);
                return decision;
            }

            Map<String, Object> context = buildProgressContext(issue, recentCount, timeline);
            ProgressJudge judge = sanitizeProgressJudge(judgeProgress(context), issue);
            toolCalls.add(Map.of(
                    "tool", "judgeZenTaoAction",
                    "zentaoAction", judge.zentaoAction(),
                    "reason", judge.reason()
            ));

            ZenTaoExecution zentaoExecution = executeZenTao(issue, judge, toolCalls, thoughts);
            issueRepo.save(issue);

            String notificationReason = "飞书仅用于周报，Bug 实时告警已关闭";
            toolCalls.add(Map.of(
                    "tool", "skipNotification",
                    "status", "DISABLED",
                    "reason", notificationReason
            ));
            thoughts.add(notificationReason);

            AiIssueDecision decision = AiIssueDecision.builder()
                    .action("PROGRESS_ISSUE")
                    .reason(judge.reason())
                    .notificationSent(false)
                    .recentCount(recentCount)
                    .plan(plan)
                    .zentaoAction(zentaoExecution.action())
                    .zentaoReason(judge.reason())
                    .zentaoIssueKey(value(issue.getRelatedIssue()))
                    .notificationReason(notificationReason)
                    .notificationMessage("")
                    .build();
            thoughts.add("推进完成，禅道动作=" + zentaoExecution.action() + "，飞书实时告警保持关闭。");
            runLogger.success(run, thoughts, toolCalls, decision);
            return decision;
        } catch (Exception e) {
            runLogger.failed(run, thoughts, toolCalls, e);
            log.warn("Issue progress decision failed for issue {}", issue.getId(), e);
            return AiIssueDecision.builder()
                    .action("NOOP")
                    .reason("问题推进判断失败: " + e.getMessage())
                    .build();
        }
    }

    private ProgressJudge judgeProgress(Map<String, Object> context) {
        aiRateLimiter.acquireLlm();
        String response = chatClientBuilder.build().prompt()
                .system(progressJudgePrompt())
                .user(toJson(context))
                .call()
                .content();
        JsonNode node = parseJsonObject(response);
        if (node == null) {
            return ProgressJudge.noop("模型未返回有效 JSON");
        }
        return new ProgressJudge(
                text(node, "zentaoAction"),
                text(node, "zentaoTitle"),
                text(node, "zentaoContent"),
                text(node, "reason")
        );
    }

    private ProgressJudge sanitizeProgressJudge(ProgressJudge judge, FeedbackIssue issue) {
        String zentaoAction;
        if (!isActive(issue) || issue.getCategory() != FeedbackCategoryEnum.BUG) {
            zentaoAction = "NOOP";
        } else if (issue.getRelatedIssue() == null || issue.getRelatedIssue().isBlank()) {
            zentaoAction = "CREATE_BUG";
        } else {
            zentaoAction = "APPEND_COMMENT";
        }

        return new ProgressJudge(
                zentaoAction,
                defaultIfBlank(judge.zentaoTitle(), issue.getTitle()),
                defaultIfBlank(judge.zentaoContent(), issue.getAiSummary()),
                defaultIfBlank(judge.reason(), "根据问题上下文完成推进判断")
        );
    }

    private ZenTaoExecution executeZenTao(FeedbackIssue issue,
                                          ProgressJudge judge,
                                          List<Map<String, Object>> toolCalls,
                                          List<String> thoughts) {
        if ("CREATE_BUG".equals(judge.zentaoAction())) {
            String issueKey = zenTaoService.createBug(issue, judge.zentaoTitle(), judge.zentaoContent());
            boolean created = issueKey != null && !issueKey.isBlank();
            if (created) {
                issue.setRelatedIssue(issueKey);
                timelineRepo.save(IssueTimeline.builder()
                        .issueId(issue.getId())
                        .eventType("zentao_bug_created")
                        .detail("创建禅道 Bug：" + issueKey + "，原因：" + judge.reason())
                        .build());
                thoughts.add("已创建禅道 Bug：" + issueKey);
            } else {
                thoughts.add("禅道 Bug 创建失败或被跳过。");
            }
            toolCalls.add(Map.of(
                    "tool", "createZenTaoBug",
                    "status", created ? "SUCCESS" : "SKIPPED",
                    "issueKey", value(issueKey)
            ));
            return new ZenTaoExecution(created ? "CREATE_BUG" : "NOOP", value(issueKey));
        }

        if ("APPEND_COMMENT".equals(judge.zentaoAction())) {
            boolean appended = zenTaoService.syncBugUpdate(issue, judge.zentaoContent());
            if (appended) {
                timelineRepo.save(IssueTimeline.builder()
                        .issueId(issue.getId())
                        .eventType("zentao_comment_appended")
                        .detail("追加禅道备注，原因：" + judge.reason())
                        .build());
            }
            toolCalls.add(Map.of(
                    "tool", "syncZenTaoBug",
                    "status", appended ? "SUCCESS" : "SKIPPED",
                    "issueKey", value(issue.getRelatedIssue())
            ));
            thoughts.add(appended
                    ? "已追加禅道备注：" + value(issue.getRelatedIssue())
                    : "禅道备注未追加。");
            return new ZenTaoExecution(appended ? "APPEND_COMMENT" : "NOOP", value(issue.getRelatedIssue()));
        }

        toolCalls.add(Map.of("tool", "skipZenTaoAction", "status", "NOOP", "reason", judge.reason()));
        thoughts.add("当前问题无需禅道动作。");
        return new ZenTaoExecution("NOOP", value(issue.getRelatedIssue()));
    }

    private Map<String, Object> buildProgressContext(FeedbackIssue issue,
                                                     long recentCount,
                                                     List<IssueTimeline> timeline) {
        Map<String, Object> context = baseIssueContext(issue, recentCount);
        context.put("task", "生成禅道 Bug 标题、描述或备注内容；不要修改 Issue 严重度。");
        context.put("timeline", timeline.stream().limit(8).map(this::timelineSnapshot).toList());
        context.put("businessRules", List.of(
                "只有 Bug 类型问题进入禅道",
                "Bug 问题未关联禅道编号时创建 Bug",
                "已关联禅道编号时追加一次汇总备注",
                "反馈数和近期新增只作为人工预警信号，不自动升级严重度",
                "飞书只发送周报，不发送单个 Bug 告警"
        ));
        return context;
    }

    private Map<String, Object> baseIssueContext(FeedbackIssue issue, long recentCount) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("issueId", issue.getId());
        context.put("title", value(issue.getTitle()));
        context.put("category", issue.getCategory() != null ? issue.getCategory().name() : "");
        context.put("module", value(issue.getModule()));
        context.put("severity", value(issue.getSeverity()));
        context.put("priority", value(issue.getPriority()));
        context.put("status", issue.getStatus() != null ? issue.getStatus().name() : "");
        context.put("reportCount", issue.getReportCount() != null ? issue.getReportCount() : 0);
        context.put("recentCountIn24h", recentCount);
        context.put("relatedIssue", value(issue.getRelatedIssue()));
        context.put("affectVersions", value(issue.getAffectVersions()));
        context.put("summary", value(issue.getAiSummary()));
        context.put("typicalContent", value(issue.getTypicalContent()));
        return context;
    }

    private Map<String, Object> timelineSnapshot(IssueTimeline item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventType", value(item.getEventType()));
        map.put("detail", value(item.getDetail()));
        map.put("createdAt", item.getCreatedAt() != null ? item.getCreatedAt().toString() : "");
        return map;
    }

    private String progressJudgePrompt() {
        return promptTemplateService.load("issue-progress.md");
    }

    private boolean isActive(FeedbackIssue issue) {
        return issue.getStatus() != IssueStatusEnum.RESOLVED
                && issue.getStatus() != null
                && !issue.getStatus().isArchivedStatus();
    }

    private JsonNode parseJsonObject(String content) {
        try {
            if (content == null) {
                return null;
            }
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            return objectMapper.readTree(content.substring(start, end + 1));
        } catch (Exception e) {
            log.warn("Failed to parse issue progress JSON: {}", content, e);
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("");
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? value(fallback) : value.trim();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record ProgressJudge(String zentaoAction, String zentaoTitle, String zentaoContent, String reason) {
        static ProgressJudge noop(String reason) {
            return new ProgressJudge("NOOP", "", "", reason);
        }
    }

    private record ZenTaoExecution(String action, String issueKey) {
    }
}
