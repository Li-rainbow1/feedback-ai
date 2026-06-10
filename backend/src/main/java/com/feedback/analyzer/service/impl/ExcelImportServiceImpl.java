package com.feedback.analyzer.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import com.feedback.analyzer.service.ExcelImportService;
import com.feedback.analyzer.service.FeedbackRawIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class ExcelImportServiceImpl implements ExcelImportService {

    private final FeedbackRawIngestionService rawIngestionService;

    public ExcelImportServiceImpl(FeedbackRawIngestionService rawIngestionService) {
        this.rawIngestionService = rawIngestionService;
    }

    @Override
    public int importFromExcel(Long productId, String channel, MultipartFile file) {
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        try (InputStream is = file.getInputStream()) {
            EasyExcel.read(is, FeedbackExcelRow.class, new ReadListener<FeedbackExcelRow>() {
                final List<FeedbackExcelRow> batch = new ArrayList<>(100);

                @Override
                public void invoke(FeedbackExcelRow row, AnalysisContext ctx) {
                    row.setRowNumber(ctx.readRowHolder().getRowIndex() + 1);
                    batch.add(row);
                    if (batch.size() >= 100) {
                        processBatch(productId, channel, batch, count);
                        batch.clear();
                    }
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext ctx) {
                    if (!batch.isEmpty()) {
                        processBatch(productId, channel, batch, count);
                    }
                    latch.countDown();
                }
            }).sheet().headRowNumber(1).doRead();
        } catch (Exception e) {
            log.error("Excel import failed", e);
            throw new RuntimeException("Excel导入失败: " + e.getMessage());
        }

        try { latch.await(); } catch (InterruptedException ignored) {}
        return count.get();
    }

    private void processBatch(Long productId, String channel, List<FeedbackExcelRow> rows, AtomicInteger count) {
        for (FeedbackExcelRow row : rows) {
            if (isEmptyRow(row)) {
                continue;
            }
            if (isBlank(row.getContent())) {
                throw new RuntimeException("Excel第" + row.getRowNumber() + "行反馈内容不能为空");
            }
            FeedbackRaw raw = FeedbackRaw.builder()
                    .id("RAW-IMP-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8))
                    .productId(productId)
                    .channel(channel)
                    .rawContent(row.getContent().trim())
                    .star(normalizeStar(row.getStar(), row.getRowNumber()))
                    .userId(row.getUserId())
                    .userName(row.getUserName())
                    .appVersion(row.getAppVersion())
                    .deviceInfo(row.getDevice())
                    .feedbackTime(parseTime(row.getTime(), row.getRowNumber()))
                    .status(FeedbackStatusEnum.RAW)
                    .build();

            rawIngestionService.saveAndPublish(raw);
            count.incrementAndGet();
        }
    }

    private Integer normalizeStar(Integer star, int rowNumber) {
        if (star == null) {
            return null;
        }
        if (star < 1 || star > 5) {
            throw new RuntimeException("Excel第" + rowNumber + "行满意度评分必须在1到5之间");
        }
        return star;
    }

    private LocalDateTime parseTime(String value, int rowNumber) {
        if (isBlank(value)) {
            return LocalDateTime.now();
        }
        String text = value.trim();
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        );
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (Exception ignored) {
            }
        }
        try {
            return OffsetDateTime.parse(text).toLocalDateTime();
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(text).atStartOfDay();
        } catch (Exception ignored) {
        }
        throw new RuntimeException("Excel第" + rowNumber + "行反馈时间格式错误，推荐 yyyy-MM-dd HH:mm:ss");
    }

    private boolean isEmptyRow(FeedbackExcelRow row) {
        return isBlank(row.getContent())
                && isBlank(row.getUserId())
                && isBlank(row.getUserName())
                && isBlank(row.getAppVersion())
                && isBlank(row.getDevice())
                && row.getStar() == null
                && row.getTime() == null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @lombok.Data
    public static class FeedbackExcelRow {
        @com.alibaba.excel.annotation.ExcelIgnore
        private int rowNumber;

        @com.alibaba.excel.annotation.ExcelProperty("反馈内容")
        private String content;

        @com.alibaba.excel.annotation.ExcelProperty("用户ID")
        private String userId;

        @com.alibaba.excel.annotation.ExcelProperty("用户名")
        private String userName;

        @com.alibaba.excel.annotation.ExcelProperty("App版本")
        private String appVersion;

        @com.alibaba.excel.annotation.ExcelProperty("设备信息")
        private String device;

        @com.alibaba.excel.annotation.ExcelProperty("满意度评分")
        private Integer star;

        @com.alibaba.excel.annotation.ExcelProperty(value = "反馈时间")
        private String time;
    }
}
