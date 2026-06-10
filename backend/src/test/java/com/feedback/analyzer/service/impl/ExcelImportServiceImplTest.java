package com.feedback.analyzer.service.impl;

import com.alibaba.excel.EasyExcel;
import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.service.FeedbackRawIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExcelImportServiceImplTest {

    @Mock
    private FeedbackRawIngestionService rawIngestionService;

    private ExcelImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ExcelImportServiceImpl(rawIngestionService);
    }

    @Test
    void importsRowWithoutSatisfactionRating() {
        ExcelImportServiceImpl.FeedbackExcelRow row = row("登录页面提交后没有反应");
        row.setTime("2026-05-25 09:25:00");

        int imported = service.importFromExcel(1L, "excel", excelFile(row));

        assertThat(imported).isEqualTo(1);
        ArgumentCaptor<FeedbackRaw> captor = ArgumentCaptor.forClass(FeedbackRaw.class);
        verify(rawIngestionService).saveAndPublish(captor.capture());
        FeedbackRaw saved = captor.getValue();
        assertThat(saved.getRawContent()).isEqualTo("登录页面提交后没有反应");
        assertThat(saved.getStar()).isNull();
        assertThat(saved.getFeedbackTime()).isEqualTo(LocalDateTime.of(2026, 5, 25, 9, 25));
    }

    @Test
    void rejectsOutOfRangeSatisfactionRating() {
        ExcelImportServiceImpl.FeedbackExcelRow row = row("分享页面闪退");
        row.setStar(6);

        assertThatThrownBy(() -> service.importFromExcel(1L, "excel", excelFile(row)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Excel第2行满意度评分必须在1到5之间");

        verify(rawIngestionService, never()).saveAndPublish(any());
    }

    @Test
    void rejectsNonEmptyRowWithoutFeedbackContent() {
        ExcelImportServiceImpl.FeedbackExcelRow row = row("");
        row.setUserId("u1001");

        assertThatThrownBy(() -> service.importFromExcel(1L, "excel", excelFile(row)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Excel第2行反馈内容不能为空");

        verify(rawIngestionService, never()).saveAndPublish(any());
    }

    private ExcelImportServiceImpl.FeedbackExcelRow row(String content) {
        ExcelImportServiceImpl.FeedbackExcelRow row = new ExcelImportServiceImpl.FeedbackExcelRow();
        row.setContent(content);
        return row;
    }

    private MockMultipartFile excelFile(ExcelImportServiceImpl.FeedbackExcelRow... rows) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output, ExcelImportServiceImpl.FeedbackExcelRow.class)
                .sheet()
                .doWrite(List.of(rows));
        return new MockMultipartFile(
                "file",
                "feedback.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
    }
}
