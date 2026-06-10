package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.entity.PublicReviewCollectRun;
import com.feedback.analyzer.entity.PublicReviewSource;
import com.feedback.analyzer.model.dto.PublicReviewItem;
import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import com.feedback.analyzer.repository.ProductRepository;
import com.feedback.analyzer.repository.PublicReviewCollectRunRepository;
import com.feedback.analyzer.repository.PublicReviewSourceRepository;
import com.feedback.analyzer.service.FeedbackRawIngestionService;
import com.feedback.analyzer.service.PublicReviewCollector;
import com.feedback.analyzer.service.PublicReviewSourceService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PublicReviewSourceServiceImpl implements PublicReviewSourceService {

    private static final List<Integer> ALLOWED_INITIAL_LIMITS = List.of(50, 100, 200);
    private static final List<String> ACTIVE_RUN_STATUSES = List.of("RUNNING", "PROCESSING");

    private final PublicReviewSourceRepository sourceRepo;
    private final PublicReviewCollectRunRepository runRepo;
    private final FeedbackRawRepository rawRepo;
    private final FeedbackRawIngestionService rawIngestionService;
    private final ProductRepository productRepo;
    private final List<PublicReviewCollector> collectors;
    private final ObjectMapper objectMapper;
    private final RedissonClient redisson;
    private final Executor publicReviewExecutor;
    private final long collectTimeoutMinutes;
    private final long processingHardTimeoutHours;

    public PublicReviewSourceServiceImpl(PublicReviewSourceRepository sourceRepo,
                                         PublicReviewCollectRunRepository runRepo,
                                         FeedbackRawRepository rawRepo,
                                         FeedbackRawIngestionService rawIngestionService,
                                         ProductRepository productRepo,
                                         List<PublicReviewCollector> collectors,
                                         ObjectMapper objectMapper,
                                         RedissonClient redisson,
                                         @Qualifier("publicReviewTaskExecutor") Executor publicReviewExecutor,
                                         @Value("${public-review.collect-timeout-minutes:30}") long collectTimeoutMinutes,
                                         @Value("${public-review.processing-hard-timeout-hours:6}") long processingHardTimeoutHours) {
        this.sourceRepo = sourceRepo;
        this.runRepo = runRepo;
        this.rawRepo = rawRepo;
        this.rawIngestionService = rawIngestionService;
        this.productRepo = productRepo;
        this.collectors = collectors;
        this.objectMapper = objectMapper;
        this.redisson = redisson;
        this.publicReviewExecutor = publicReviewExecutor;
        this.collectTimeoutMinutes = collectTimeoutMinutes;
        this.processingHardTimeoutHours = processingHardTimeoutHours;
    }

    @Override
    public List<PublicReviewSource> list(Long productId) {
        markStaleActiveRuns();
        List<PublicReviewSource> sources = sourceRepo.findByProductIdOrderByCreatedAtDesc(productId);
        sources.forEach(this::attachRuntimeState);
        return sources;
    }

    @Override
    public PublicReviewSource get(Long id) {
        PublicReviewSource source = sourceRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("公开评论来源不存在：" + id));
        attachRuntimeState(source);
        return source;
    }

    @Override
    public PublicReviewSource create(PublicReviewSource source) {
        normalizeAndValidate(source, null);
        if (sourceRepo.existsByProductIdAndPlatformAndAppId(
                source.getProductId(), source.getPlatform(), source.getAppId())) {
            throw new RuntimeException("该产品已配置相同公开评论来源");
        }
        source.setId(null);
        source.setInitialized(false);
        return sourceRepo.save(source);
    }

    @Override
    public PublicReviewSource update(Long id, PublicReviewSource request) {
        PublicReviewSource source = get(id);
        normalizeAndValidate(request, id);
        if (sourceRepo.existsByProductIdAndPlatformAndAppIdAndIdNot(
                request.getProductId(), request.getPlatform(), request.getAppId(), id)) {
            throw new RuntimeException("该产品已配置相同公开评论来源");
        }
        if (rawRepo.countByPublicReviewSourceId(id) > 0
                && (!source.getPlatform().equals(request.getPlatform())
                || !source.getAppId().equals(request.getAppId())
                || !source.getProductId().equals(request.getProductId()))) {
            throw new RuntimeException("已有评论数据的来源不能更换平台、应用或所属产品");
        }
        source.setName(request.getName());
        source.setProductId(request.getProductId());
        source.setPlatform(request.getPlatform());
        source.setAppId(request.getAppId());
        source.setRegion(request.getRegion());
        source.setLanguage(request.getLanguage());
        source.setInitializationLimit(request.getInitializationLimit());
        source.setEnabled(request.getEnabled());
        source.setScheduledEnabled(request.getScheduledEnabled());
        return sourceRepo.save(source);
    }

    @Override
    public void delete(Long id) {
        if (rawRepo.countByPublicReviewSourceId(id) > 0) {
            throw new RuntimeException("该来源已有评论数据，请停用后保留采集记录");
        }
        if (hasActiveRun(id)) {
            throw new RuntimeException("该来源存在进行中的采集或分析任务，不能删除");
        }
        sourceRepo.delete(get(id));
    }

    @Override
    public List<PublicReviewItem> preview(Long id) {
        PublicReviewSource source = get(id);
        return collector(source).collect(source, 5);
    }

    @Override
    public PublicReviewCollectRun initialize(Long id) {
        PublicReviewSource source = get(id);
        if (Boolean.TRUE.equals(source.getInitialized())) {
            throw new RuntimeException("该来源已经初始化，可使用立即采集获取新增评论");
        }
        return submitCollect(source, "INITIALIZE", source.getInitializationLimit());
    }

    @Override
    public PublicReviewCollectRun collect(Long id, String runType) {
        PublicReviewSource source = get(id);
        if (!Boolean.TRUE.equals(source.getInitialized())) {
            throw new RuntimeException("请先执行首次初始化");
        }
        return submitCollect(source, normalizeRunType(runType), 100);
    }

    @Override
    public List<PublicReviewCollectRun> runs(Long id) {
        get(id);
        markStaleActiveRuns();
        return runRepo.findBySourceIdOrderByStartedAtDesc(id);
    }

    @Override
    public void collectScheduled() {
        for (PublicReviewSource source : sourceRepo.findByEnabledTrueAndScheduledEnabledTrueAndInitializedTrue()) {
            try {
                submitCollect(source, "SCHEDULED", 100);
            } catch (Exception e) {
                log.warn("公开评论定时采集失败，sourceId={}: {}", source.getId(), e.getMessage());
            }
        }
    }

    private PublicReviewCollectRun submitCollect(PublicReviewSource source, String runType, int limit) {
        if (!Boolean.TRUE.equals(source.getEnabled())) {
            throw new RuntimeException("公开评论来源已停用");
        }
        markStaleActiveRuns();
        if (hasActiveRun(source.getId())) {
            throw new RuntimeException("该来源正在采集或分析中，请稍后查看执行记录");
        }

        PublicReviewCollectRun run = runRepo.save(PublicReviewCollectRun.builder()
                .sourceId(source.getId())
                .productId(source.getProductId())
                .runType(runType)
                .status("RUNNING")
                .build());
        publicReviewExecutor.execute(() -> executeCollectRun(run.getId(), source.getId(), runType, limit));
        return run;
    }

    private void markStaleActiveRuns() {
        LocalDateTime collectThreshold = LocalDateTime.now().minusMinutes(collectTimeoutMinutes);
        for (PublicReviewCollectRun staleRun : runRepo.findByStatusInAndStartedAtBefore(ACTIVE_RUN_STATUSES, collectThreshold)) {
            if ("RUNNING".equals(staleRun.getStatus())) {
                staleRun.setStatus("FAILED");
                staleRun.setErrorMessage("采集任务超过 " + collectTimeoutMinutes + " 分钟未完成，系统已自动结束");
                staleRun.setFinishedAt(LocalDateTime.now());
                runRepo.save(staleRun);
            }
        }

        LocalDateTime processingThreshold = LocalDateTime.now().minusHours(processingHardTimeoutHours);
        for (PublicReviewCollectRun staleRun : runRepo.findByStatusInAndStartedAtBefore(ACTIVE_RUN_STATUSES, processingThreshold)) {
            if ("PROCESSING".equals(staleRun.getStatus())) {
                staleRun.setStatus("FAILED");
                staleRun.setErrorMessage("分析任务超过 " + processingHardTimeoutHours + " 小时仍未完成，系统已自动结束");
                staleRun.setFinishedAt(LocalDateTime.now());
                runRepo.save(staleRun);
            }
        }
    }

    private void executeCollectRun(Long runId, Long sourceId, String runType, int limit) {
        PublicReviewSource source = get(sourceId);
        PublicReviewCollectRun run = runRepo.findById(runId)
                .orElseThrow(() -> new RuntimeException("公开评论采集记录不存在：" + runId));
        RLock lock = redisson.getLock("public-review:collect:" + source.getId());
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                throw new RuntimeException("该来源正在采集或分析中，请稍后查看执行记录");
            }
            List<PublicReviewItem> items = collector(source).collect(source, limit);
            int inserted = 0;
            int duplicate = 0;
            List<FeedbackRaw> insertedItems = new ArrayList<>();
            for (PublicReviewItem item : items) {
                if (rawRepo.existsByPublicReviewSourceIdAndExternalReviewId(source.getId(), item.externalId())) {
                    duplicate++;
                    continue;
                }
                try {
                    FeedbackRaw raw = toRaw(source, run.getId(), item);
                    rawIngestionService.saveAndFlush(raw);
                    insertedItems.add(raw);
                    inserted++;
                } catch (DataIntegrityViolationException e) {
                    duplicate++;
                }
            }
            run.setFetchedCount(items.size());
            run.setNewCount(inserted);
            run.setDuplicateCount(duplicate);
            run.setStatus(inserted == 0 ? "SUCCESS" : "PROCESSING");
            if (inserted == 0) {
                run.setFinishedAt(LocalDateTime.now());
            }
            runRepo.save(run);

            source.setLastCollectedAt(LocalDateTime.now());
            source.setLastSuccessAt(LocalDateTime.now());
            source.setLastError(null);
            source.setLastNewCount(inserted);
            if ("INITIALIZE".equals(runType)) {
                source.setInitialized(true);
            }
            sourceRepo.save(source);
            insertedItems.forEach(rawIngestionService::publish);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markCollectFailed(source, run, "采集任务被中断");
        } catch (Exception e) {
            log.warn("公开评论采集失败，sourceId={}, runId={}: {}", source.getId(), runId, e.getMessage());
            markCollectFailed(source, run, e.getMessage());
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void markCollectFailed(PublicReviewSource source, PublicReviewCollectRun run, String message) {
        String error = message == null || message.isBlank() ? "采集失败" : message;
        source.setLastCollectedAt(LocalDateTime.now());
        source.setLastError(error);
        sourceRepo.save(source);
        run.setStatus("FAILED");
        run.setErrorMessage(error);
        run.setFinishedAt(LocalDateTime.now());
        runRepo.save(run);
    }

    private FeedbackRaw toRaw(PublicReviewSource source, Long runId, PublicReviewItem item) {
        String metadata;
        try {
            metadata = objectMapper.writeValueAsString(item.metadata());
        } catch (Exception e) {
            metadata = "{}";
        }
        return FeedbackRaw.builder()
                .id("RAW-PUB-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8))
                .productId(source.getProductId())
                .channel(source.getName())
                .sourceType(source.getPlatform())
                .publicReviewSourceId(source.getId())
                .collectionRunId(runId)
                .externalReviewId(item.externalId())
                .sourceMetadata(metadata)
                .rawContent(item.content())
                .userId(item.userId())
                .userName(item.userName())
                .star(item.star())
                .appVersion(item.appVersion())
                .deviceInfo(item.deviceInfo())
                .feedbackTime(item.feedbackTime() != null ? item.feedbackTime() : LocalDateTime.now())
                .status(FeedbackStatusEnum.RAW)
                .build();
    }

    private PublicReviewCollector collector(PublicReviewSource source) {
        return collectors.stream()
                .filter(item -> item.supports(source.getPlatform()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("未找到平台采集器：" + source.getPlatform()));
    }

    private void normalizeAndValidate(PublicReviewSource source, Long id) {
        if (source.getProductId() == null || !productRepo.existsById(source.getProductId())) {
            throw new RuntimeException("请选择有效产品");
        }
        if (source.getName() == null || source.getName().isBlank()) {
            throw new RuntimeException("请输入来源名称");
        }
        String platform = source.getPlatform() == null || source.getPlatform().isBlank()
                ? "STEAM" : source.getPlatform().trim().toUpperCase(Locale.ROOT);
        if (!"STEAM".equals(platform)) {
            throw new RuntimeException("当前仅支持 Steam 公开评论采集");
        }
        source.setPlatform(platform);
        if (source.getAppId() == null || source.getAppId().isBlank()) {
            throw new RuntimeException("请输入应用 ID");
        }
        source.setAppId(source.getAppId().trim());
        Integer limit = source.getInitializationLimit() == null ? 100 : source.getInitializationLimit();
        if (!ALLOWED_INITIAL_LIMITS.contains(limit)) {
            throw new RuntimeException("首次采集条数仅支持 50、100 或 200");
        }
        source.setInitializationLimit(limit);
        source.setRegion(null);
        source.setLanguage(defaultValue(source.getLanguage(), "schinese"));
        if (source.getEnabled() == null) {
            source.setEnabled(true);
        }
        if (source.getScheduledEnabled() == null) {
            source.setScheduledEnabled(true);
        }
    }

    private void attachRuntimeState(PublicReviewSource source) {
        long rawCount = rawRepo.countByPublicReviewSourceId(source.getId());
        source.setHasReviewData(rawCount > 0);
        List<PublicReviewCollectRun> activeRuns = runRepo.findBySourceIdAndStatusInOrderByStartedAtDesc(
                source.getId(), ACTIVE_RUN_STATUSES);
        source.setBusy(!activeRuns.isEmpty());
        source.setActiveRunStatus(activeRuns.isEmpty() ? null : activeRuns.get(0).getStatus());
    }

    private boolean hasActiveRun(Long sourceId) {
        return runRepo.existsBySourceIdAndStatusIn(sourceId, ACTIVE_RUN_STATUSES);
    }

    private String normalizeRunType(String runType) {
        return "SCHEDULED".equalsIgnoreCase(runType) ? "SCHEDULED" : "MANUAL";
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
