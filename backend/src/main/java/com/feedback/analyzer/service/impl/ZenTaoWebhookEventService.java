package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.entity.IssueTimeline;
import com.feedback.analyzer.entity.ZenTaoWebhookEvent;
import com.feedback.analyzer.model.dto.ZenTaoBugSnapshot;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.repository.FeedbackIssueRepository;
import com.feedback.analyzer.repository.IssueTimelineRepository;
import com.feedback.analyzer.repository.ZenTaoWebhookEventRepository;
import com.feedback.analyzer.service.ZenTaoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZenTaoWebhookEventService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_RETRY = "RETRY";
    private static final String STATUS_FAILED = "FAILED";
    private static final int MAX_RETRY = 10;

    private final ZenTaoWebhookEventRepository eventRepo;
    private final FeedbackIssueRepository issueRepo;
    private final IssueTimelineRepository timelineRepo;
    private final ZenTaoService zenTaoService;
    private final RedissonClient redisson;

    public ZenTaoWebhookEvent record(JsonNode root, String payload, String issueKey) {
        ZenTaoWebhookEvent event = ZenTaoWebhookEvent.builder()
                .issueKey(issueKey)
                .objectType(firstText(root, "objectType", "object_type"))
                .objectId(extractObjectId(root))
                .action(firstText(root, "action"))
                .payload(payload)
                .status(STATUS_PENDING)
                .retryCount(0)
                .nextRetryAt(LocalDateTime.now())
                .receivedAt(LocalDateTime.now())
                .build();
        return eventRepo.save(event);
    }

    public void processDueEvents() {
        RLock workerLock = redisson.getLock("zentao:webhook:worker");
        if (!workerLock.tryLock()) {
            return;
        }
        try {
            resetStuckProcessingEvents();
            List<ZenTaoWebhookEvent> events = eventRepo
                    .findByStatusInAndNextRetryAtLessThanEqualOrderByReceivedAtAsc(
                            List.of(STATUS_PENDING, STATUS_RETRY),
                            LocalDateTime.now(),
                            PageRequest.of(0, 20));
            for (ZenTaoWebhookEvent event : events) {
                processOne(event);
            }
        } finally {
            if (workerLock.isHeldByCurrentThread()) {
                workerLock.unlock();
            }
        }
    }

    private void resetStuckProcessingEvents() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        List<ZenTaoWebhookEvent> stuck = eventRepo.findByStatusAndUpdatedAtBefore(
                STATUS_PROCESSING, threshold, PageRequest.of(0, 50));
        for (ZenTaoWebhookEvent event : stuck) {
            event.setStatus(STATUS_RETRY);
            event.setNextRetryAt(LocalDateTime.now());
            event.setErrorMessage("事件处理超时，已重新排队");
            eventRepo.save(event);
        }
    }

    private void processOne(ZenTaoWebhookEvent event) {
        try {
            event.setStatus(STATUS_PROCESSING);
            event.setErrorMessage(null);
            eventRepo.save(event);

            FeedbackIssue issue = issueRepo.findByRelatedIssue(event.getIssueKey()).orElse(null);
            if (issue == null) {
                log.debug("ZenTao webhook event skipped because issue is not linked: {}", event.getIssueKey());
                markSuccess(event);
                return;
            }

            ZenTaoBugSnapshot snapshot = zenTaoService.fetchBugSnapshot(event.getIssueKey());
            syncIssue(issue, snapshot);
            markSuccess(event);
        } catch (Exception e) {
            scheduleRetry(event, e);
        }
    }

    private void syncIssue(FeedbackIssue issue, ZenTaoBugSnapshot snapshot) {
        boolean changed = false;
        IssueStatusEnum newStatus = mapZenTaoStatus(snapshot.status());
        if (newStatus != null && canApplyStatus(issue, newStatus)) {
            IssueStatusEnum oldStatus = issue.getStatus();
            issue.setStatus(newStatus);
            if (newStatus == IssueStatusEnum.RESOLVED && issue.getResolvedAt() == null) {
                issue.setResolvedAt(LocalDateTime.now());
            } else if ((oldStatus == IssueStatusEnum.RESOLVED || oldStatus == IssueStatusEnum.CLOSED)
                    && newStatus != IssueStatusEnum.RESOLVED && newStatus != IssueStatusEnum.CLOSED) {
                issue.setResolvedAt(null);
            }
            changed = true;
            timelineRepo.save(IssueTimeline.builder()
                    .issueId(issue.getId())
                    .eventType("zentao_status_synced")
                    .detail("禅道 " + snapshot.issueKey() + " 状态已同步为：" + newStatus.getLabel())
                    .build());
        }

        if (Boolean.TRUE.equals(snapshot.confirmed()) && !Boolean.TRUE.equals(issue.getConfirmed())) {
            issue.setConfirmed(true);
            changed = true;
            timelineRepo.save(IssueTimeline.builder()
                    .issueId(issue.getId())
                    .eventType("zentao_confirmed_synced")
                    .detail("禅道 " + snapshot.issueKey() + " 是否确认已同步为：已确认")
                    .build());
        } else if (Boolean.FALSE.equals(snapshot.confirmed()) && Boolean.TRUE.equals(issue.getConfirmed())) {
            log.info("Skip ZenTao unconfirmed downgrade for issue {} ({})", issue.getId(), snapshot.issueKey());
        }

        boolean triageChanged = false;
        if (snapshot.severity() != null && !Objects.equals(issue.getSeverity(), snapshot.severity())) {
            issue.setSeverity(snapshot.severity());
            triageChanged = true;
        }
        if (snapshot.priority() != null && !Objects.equals(issue.getPriority(), snapshot.priority())) {
            issue.setPriority(snapshot.priority());
            triageChanged = true;
        }
        if (triageChanged) {
            issue.setTriageSource("ZENTAO_WEBHOOK");
            issue.setTriageReason("禅道回流：" + snapshot.issueKey() + " 严重度/优先级已同步");
            changed = true;
            timelineRepo.save(IssueTimeline.builder()
                    .issueId(issue.getId())
                    .eventType("zentao_triage_synced")
                    .detail("禅道回流等级：" + value(issue.getSeverity(), "-")
                            + " / " + value(issue.getPriority(), "-"))
                    .build());
        }

        if (changed) {
            issueRepo.save(issue);
            log.info("Issue {} synced from ZenTao webhook event {}", issue.getId(), snapshot.issueKey());
        } else {
            log.debug("ZenTao webhook event has no local changes for issue {} ({})",
                    issue.getId(), snapshot.issueKey());
        }
    }

    private void markSuccess(ZenTaoWebhookEvent event) {
        event.setStatus(STATUS_SUCCESS);
        event.setProcessedAt(LocalDateTime.now());
        event.setErrorMessage(null);
        eventRepo.save(event);
    }

    private void scheduleRetry(ZenTaoWebhookEvent event, Exception e) {
        int retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
        retryCount++;
        event.setRetryCount(retryCount);
        event.setErrorMessage(truncate(e.getMessage(), 1000));
        if (retryCount >= MAX_RETRY) {
            event.setStatus(STATUS_FAILED);
            event.setProcessedAt(LocalDateTime.now());
        } else {
            event.setStatus(STATUS_RETRY);
            event.setNextRetryAt(LocalDateTime.now().plusMinutes(backoffMinutes(retryCount)));
        }
        eventRepo.save(event);
        log.warn("ZenTao webhook event {} failed, retryCount={}, issueKey={}, error={}",
                event.getId(), retryCount, event.getIssueKey(), e.getMessage());
    }

    private long backoffMinutes(int retryCount) {
        if (retryCount <= 1) {
            return 1;
        }
        if (retryCount == 2) {
            return 2;
        }
        if (retryCount == 3) {
            return 5;
        }
        if (retryCount == 4) {
            return 10;
        }
        return 15;
    }

    private boolean canApplyStatus(FeedbackIssue issue, IssueStatusEnum newStatus) {
        IssueStatusEnum current = issue.getStatus();
        if (current == newStatus) {
            return false;
        }
        if (current != null && current.isArchivedStatus()) {
            return false;
        }
        return newStatus.isBugWorkflowStatus();
    }

    private IssueStatusEnum mapZenTaoStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim().toLowerCase()) {
            case "resolved", "done", "fixed", "已解决" -> IssueStatusEnum.RESOLVED;
            case "closed", "已关闭" -> IssueStatusEnum.CLOSED;
            case "active", "open", "opened", "激活", "未解决" -> IssueStatusEnum.OPEN;
            default -> null;
        };
    }

    private String extractObjectId(JsonNode root) {
        JsonNode object = nestedObject(root);
        String oldId = firstText(object, "id", "ID", "bugId", "bugID");
        if (oldId != null) {
            return oldId;
        }
        return firstText(root, "objectID", "objectId", "bugID", "bugId", "id");
    }

    private JsonNode nestedObject(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.has("object")) {
            return root.get("object");
        }
        if (root.has("data")) {
            return root.get("data");
        }
        return null;
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asText();
            }
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
            if (value.isObject()) {
                String nested = firstText(value, "id", "value", "code", "name", "title", "label");
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
