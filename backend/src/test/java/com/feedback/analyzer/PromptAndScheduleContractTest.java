package com.feedback.analyzer;

import com.feedback.analyzer.handler.ScheduledTaskHandler;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PromptAndScheduleContractTest {

    @Test
    void feedbackAnalysisPromptRoutesPureContentRetellingToLowQuality() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("prompts/feedback-analysis.md")) {
            assertThat(input).isNotNull();
            String prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(prompt).contains("纯内容复述");
            assertThat(prompt).contains("台词/原文摘录");
            assertThat(prompt).contains("个人感悟");
            assertThat(prompt).contains("LOW_QUALITY");
            assertThat(prompt).contains("claims=[]");
        }
    }

    @Test
    void publicReviewScheduledCollectionRunsHourly() throws Exception {
        Method method = ScheduledTaskHandler.class.getDeclaredMethod("collectPublicReviews");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 0 * * * *");
    }
}
