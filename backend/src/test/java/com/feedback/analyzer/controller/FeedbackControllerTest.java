package com.feedback.analyzer.controller;

import com.feedback.analyzer.entity.ChannelConfig;
import com.feedback.analyzer.entity.FeedbackClaim;
import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.entity.Product;
import com.feedback.analyzer.model.ApiResult;
import com.feedback.analyzer.model.dto.FeedbackSubmitRequest;
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
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeedbackControllerTest {

    @Test
    void rawSearchShowsIgnoredAndLowQualityDisplayStatus() {
        FeedbackService feedbackService = mock(FeedbackService.class);
        ProductService productService = mock(ProductService.class);
        ExcelImportService excelImportService = mock(ExcelImportService.class);
        PraiseAggregationService praiseAggregationService = mock(PraiseAggregationService.class);
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        FeedbackRawRepository rawRepo = mock(FeedbackRawRepository.class);
        ChannelConfigRepository channelConfigRepo = mock(ChannelConfigRepository.class);

        FeedbackController controller = new FeedbackController(
                feedbackService, productService, excelImportService, praiseAggregationService, claimRepo, rawRepo, channelConfigRepo);

        FeedbackRaw lowQualityRaw = FeedbackRaw.builder()
                .id("RAW-1")
                .productId(1L)
                .channel("steam")
                .rawContent("好玩")
                .feedbackTime(LocalDateTime.of(2026, 6, 8, 10, 0))
                .createdAt(LocalDateTime.of(2026, 6, 8, 10, 0))
                .status(FeedbackStatusEnum.LOW_QUALITY)
                .build();
        FeedbackRaw ignoredRaw = FeedbackRaw.builder()
                .id("RAW-2")
                .productId(1L)
                .channel("steam")
                .rawContent("六月六日，六根归位")
                .feedbackTime(LocalDateTime.of(2026, 6, 8, 9, 0))
                .createdAt(LocalDateTime.of(2026, 6, 8, 9, 0))
                .status(FeedbackStatusEnum.ANALYZED)
                .build();
        FeedbackClaim ignoredClaim = FeedbackClaim.builder()
                .id("CLAIM-1")
                .rawId("RAW-2")
                .analyzedId("AN-1")
                .productId(1L)
                .claimIndex(0)
                .primaryClaim(true)
                .category(FeedbackCategoryEnum.PRAISE)
                .summary("好玩")
                .content("好玩")
                .status(FeedbackClaimStatusEnum.IGNORED)
                .decisionReason("泛泛正向短评缺少明确对象")
                .createdAt(LocalDateTime.of(2026, 6, 8, 9, 1))
                .updatedAt(LocalDateTime.of(2026, 6, 8, 9, 1))
                .build();

        when(rawRepo.searchRawFeedbacks(eq(1L), eq(null), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(lowQualityRaw, ignoredRaw)));
        when(claimRepo.findByRawIdInOrderByRawIdAscClaimIndexAsc(List.of("RAW-1", "RAW-2")))
                .thenReturn(List.of(ignoredClaim));

        ApiResult<org.springframework.data.domain.Page<Map<String, Object>>> result = controller.rawSearch(1L, null, 1, 20);
        List<Map<String, Object>> rows = result.getData().getContent();

        assertThat(rows.get(0).get("displayStatus")).isEqualTo("low_quality");
        assertThat(rows.get(0).get("displayStatusLabel")).isEqualTo("低质量");
        assertThat(rows.get(1).get("displayStatus")).isEqualTo("ignored");
        assertThat(rows.get(1).get("displayStatusLabel")).isEqualTo("已忽略");
        assertThat(rows.get(1).get("displayReason")).isEqualTo("泛泛正向短评缺少明确对象");
    }

    @Test
    void webhookRequiresValidSourceKey() {
        FeedbackService feedbackService = mock(FeedbackService.class);
        ProductService productService = mock(ProductService.class);
        ExcelImportService excelImportService = mock(ExcelImportService.class);
        PraiseAggregationService praiseAggregationService = mock(PraiseAggregationService.class);
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        FeedbackRawRepository rawRepo = mock(FeedbackRawRepository.class);
        ChannelConfigRepository channelConfigRepo = mock(ChannelConfigRepository.class);

        FeedbackController controller = new FeedbackController(
                feedbackService, productService, excelImportService, praiseAggregationService, claimRepo, rawRepo, channelConfigRepo);

        Product product = Product.builder()
                .id(1L)
                .name("测试产品")
                .enabled(true)
                .build();
        FeedbackSubmitRequest request = new FeedbackSubmitRequest();
        request.setWebhookToken("token");
        request.setSourceKey("mobile-app");
        request.setChannel("survey");
        request.setRawContent("登录后一直转圈");

        when(productService.getByWebhookToken("token")).thenReturn(product);
        when(channelConfigRepo.findByProductIdAndSourceKeyAndSourceTypeAndEnabledTrue(1L, "mobile-app", "webhook"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.webhook(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("sourceKey 无效，或对应 Webhook 入口未启用");
    }
}
