package com.feedback.analyzer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackClaim;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.FeedbackClaimStatusEnum;
import com.feedback.analyzer.repository.FeedbackClaimRepository;
import com.feedback.analyzer.service.AiAnalysisService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class PraiseAggregationServiceImplTest {

    @Test
    void groupsPraiseClaimsBySemanticSimilarityAndBackfillsMissingEmbeddings() {
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        AiAnalysisService aiService = mock(AiAnalysisService.class);
        PraiseAggregationServiceImpl service = new PraiseAggregationServiceImpl(claimRepo, aiService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.80d);

        LocalDateTime start = LocalDateTime.of(2026, 6, 2, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 8, 23, 59);

        FeedbackClaim battleA = praise("P1", "RAW-1", "战斗系统", "战斗打击感很强", "战斗打击感很强", "战斗,手感",
                "[1.0,0.0]", LocalDateTime.of(2026, 6, 8, 10, 0));
        FeedbackClaim battleB = praise("P2", "RAW-2", "整体体验", "", "战斗手感很稳，连招很顺", "手感,连招",
                null, LocalDateTime.of(2026, 6, 8, 9, 0));
        FeedbackClaim graphics = praise("P3", "RAW-3", "画面表现", "画面细节优秀", "画面细节优秀", "画面,表现",
                "[0.0,1.0]", LocalDateTime.of(2026, 6, 8, 8, 0));

        when(claimRepo.findByProductIdAndCategoryAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                1L, FeedbackCategoryEnum.PRAISE, FeedbackClaimStatusEnum.RECORDED, start, end))
                .thenReturn(List.of(battleA, battleB, graphics));
        when(aiService.generateEmbedding(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if (text.contains("画面")) {
                return List.of(0.0d, 1.0d);
            }
            return List.of(0.95d, 0.05d);
        });

        List<Map<String, Object>> groups = service.buildGroups(1L, start, end, 10);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).get("count")).isEqualTo(2);
        assertThat(groups.get(0).get("representativeSummary"))
                .isIn("战斗打击感很强", "战斗手感很稳，连招很顺");
        assertThat(String.valueOf(groups.get(0).get("keywords"))).contains("手感");
        assertThat(groups.get(1).get("count")).isEqualTo(1);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<FeedbackClaim>> savedClaims = ArgumentCaptor.forClass((Class) List.class);
        verify(claimRepo).saveAll(savedClaims.capture());
        assertThat(savedClaims.getValue()).extracting(FeedbackClaim::getId).contains("P2");
    }

    @Test
    void canResolveGroupClaimsByGroupId() {
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        AiAnalysisService aiService = mock(AiAnalysisService.class);
        PraiseAggregationServiceImpl service = new PraiseAggregationServiceImpl(claimRepo, aiService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.80d);

        LocalDateTime start = LocalDateTime.of(2026, 6, 2, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 8, 23, 59);

        FeedbackClaim battleA = praise("P1", "RAW-1", "战斗系统", "战斗打击感很强", "战斗打击感很强", "战斗,手感",
                "[1.0,0.0]", LocalDateTime.of(2026, 6, 8, 10, 0));
        FeedbackClaim battleB = praise("P2", "RAW-2", "整体体验", "连招很顺", "连招很顺", "手感,连招",
                "[0.95,0.05]", LocalDateTime.of(2026, 6, 8, 9, 0));

        when(claimRepo.findByProductIdAndCategoryAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                1L, FeedbackCategoryEnum.PRAISE, FeedbackClaimStatusEnum.RECORDED, start, end))
                .thenReturn(List.of(battleA, battleB));
        when(aiService.generateEmbedding(anyString())).thenReturn(List.of(1.0d, 0.0d));

        List<Map<String, Object>> groups = service.buildGroups(1L, start, end, 10);
        String groupId = String.valueOf(groups.get(0).get("groupId"));
        List<FeedbackClaim> claims = service.getGroupClaims(1L, start, end, groupId);

        assertThat(claims).extracting(FeedbackClaim::getId).containsExactly("P1", "P2");
    }

    @Test
    void groupsByPraiseTargetEvenWhenClaimVectorsDiffer() {
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        AiAnalysisService aiService = mock(AiAnalysisService.class);
        PraiseAggregationServiceImpl service = new PraiseAggregationServiceImpl(claimRepo, aiService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.80d);

        LocalDateTime start = LocalDateTime.of(2026, 6, 2, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 8, 23, 59);

        FeedbackClaim battleA = praise("P1", "RAW-1", "战斗系统", "战斗打击感很强", "战斗打击感很强", "战斗,手感",
                "[1.0,0.0]", LocalDateTime.of(2026, 6, 8, 10, 0));
        FeedbackClaim battleB = praise("P2", "RAW-2", "动作系统", "连招反馈很顺", "连招反馈很顺", "动作,连招",
                "[0.0,1.0]", LocalDateTime.of(2026, 6, 8, 9, 0));

        when(claimRepo.findByProductIdAndCategoryAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                1L, FeedbackCategoryEnum.PRAISE, FeedbackClaimStatusEnum.RECORDED, start, end))
                .thenReturn(List.of(battleA, battleB));
        when(aiService.generateEmbedding(anyString())).thenReturn(List.of(1.0d, 0.0d));

        List<Map<String, Object>> groups = service.buildGroups(1L, start, end, 10);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).get("count")).isEqualTo(2);
    }

    @Test
    void mergesOverallPraiseTargetsWithoutModuleAliasRules() {
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        AiAnalysisService aiService = mock(AiAnalysisService.class);
        PraiseAggregationServiceImpl service = new PraiseAggregationServiceImpl(claimRepo, aiService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.80d);

        LocalDateTime start = LocalDateTime.of(2026, 6, 2, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 8, 23, 59);

        FeedbackClaim overallA = praise("P1", "RAW-1", "游戏整体体验", "整体体验优秀", "整体体验优秀", "体验,整体",
                "[1.0,0.0]", LocalDateTime.of(2026, 6, 8, 10, 0));
        FeedbackClaim overallB = praise("P2", "RAW-2", "整体", "整体很满意", "整体很满意", "整体,满意",
                "[0.0,1.0]", LocalDateTime.of(2026, 6, 8, 9, 0));
        FeedbackClaim overallC = praise("P3", "RAW-3", "整体游戏", "整体游戏质量高", "整体游戏质量高", "整体,质量",
                "[0.0,1.0]", LocalDateTime.of(2026, 6, 8, 8, 0));
        FeedbackClaim visual = praise("P4", "RAW-4", "美术风格", "美术风格突出", "美术风格突出", "美术,视觉",
                "[0.0,1.0]", LocalDateTime.of(2026, 6, 8, 7, 0));

        when(claimRepo.findByProductIdAndCategoryAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                1L, FeedbackCategoryEnum.PRAISE, FeedbackClaimStatusEnum.RECORDED, start, end))
                .thenReturn(List.of(overallA, overallB, overallC, visual));
        when(aiService.generateEmbedding(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if (text.contains("美术")) {
                return List.of(0.0d, 1.0d);
            }
            return List.of(1.0d, 0.0d);
        });

        List<Map<String, Object>> groups = service.buildGroups(1L, start, end, 10);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).get("count")).isEqualTo(3);
        assertThat(groups.get(1).get("count")).isEqualTo(1);
        assertThat(groups.get(1).get("module")).isEqualTo("美术风格");
    }

    @Test
    void keepsDifferentPraiseTargetsSeparated() {
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        AiAnalysisService aiService = mock(AiAnalysisService.class);
        PraiseAggregationServiceImpl service = new PraiseAggregationServiceImpl(claimRepo, aiService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.80d);

        LocalDateTime start = LocalDateTime.of(2026, 6, 2, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 8, 23, 59);

        FeedbackClaim battle = praise("P1", "RAW-1", "战斗系统", "战斗反馈好", "战斗反馈好", "战斗,手感",
                "[1.0,0.0]", LocalDateTime.of(2026, 6, 8, 10, 0));
        FeedbackClaim visual = praise("P2", "RAW-2", "美术风格", "美术风格好", "美术风格好", "美术,视觉",
                "[1.0,0.0]", LocalDateTime.of(2026, 6, 8, 9, 0));

        when(claimRepo.findByProductIdAndCategoryAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                1L, FeedbackCategoryEnum.PRAISE, FeedbackClaimStatusEnum.RECORDED, start, end))
                .thenReturn(List.of(battle, visual));
        when(aiService.generateEmbedding(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if (text.contains("战斗")) {
                return List.of(1.0d, 0.0d);
            }
            return List.of(0.0d, 1.0d);
        });

        List<Map<String, Object>> groups = service.buildGroups(1L, start, end, 10);

        assertThat(groups).hasSize(2);
        assertThat(groups).extracting(group -> group.get("module"))
                .containsExactly("战斗系统", "美术风格");
    }

    @Test
    void persistsPraiseTargetEmbeddingAndReusesItOnRepeatedReads() {
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        AiAnalysisService aiService = mock(AiAnalysisService.class);
        PraiseAggregationServiceImpl service = new PraiseAggregationServiceImpl(claimRepo, aiService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.80d);

        LocalDateTime start = LocalDateTime.of(2026, 6, 2, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 8, 23, 59);

        FeedbackClaim overall = praise("P1", "RAW-1", "game overall", "overall quality is strong",
                "overall quality is strong", "overall", "[0.0,1.0]", LocalDateTime.of(2026, 6, 8, 10, 0));

        when(claimRepo.findByProductIdAndCategoryAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                1L, FeedbackCategoryEnum.PRAISE, FeedbackClaimStatusEnum.RECORDED, start, end))
                .thenReturn(List.of(overall), List.of(overall));
        when(aiService.generateEmbedding(anyString())).thenReturn(List.of(1.0d, 0.0d));

        service.buildGroups(1L, start, end, 10);
        service.buildGroups(1L, start, end, 10);

        assertThat(overall.getPraiseTarget()).isEqualTo("game overall");
        assertThat(overall.getPraiseTargetEmbeddingVector()).isNotBlank();
        verify(aiService, times(1)).generateEmbedding(anyString());
    }

    @Test
    void keepsSamePraiseTargetTogetherWhenTargetEmbeddingIsUnavailable() {
        FeedbackClaimRepository claimRepo = mock(FeedbackClaimRepository.class);
        AiAnalysisService aiService = mock(AiAnalysisService.class);
        PraiseAggregationServiceImpl service = new PraiseAggregationServiceImpl(claimRepo, aiService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.80d);

        LocalDateTime start = LocalDateTime.of(2026, 6, 2, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 8, 23, 59);

        FeedbackClaim overallA = praise("P1", "RAW-1", "game overall", "story is excellent",
                "story is excellent", "story", "[1.0,0.0]", LocalDateTime.of(2026, 6, 8, 10, 0));
        FeedbackClaim overallB = praise("P2", "RAW-2", "game overall", "art direction is excellent",
                "art direction is excellent", "art", "[0.0,1.0]", LocalDateTime.of(2026, 6, 8, 9, 0));

        when(claimRepo.findByProductIdAndCategoryAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                1L, FeedbackCategoryEnum.PRAISE, FeedbackClaimStatusEnum.RECORDED, start, end))
                .thenReturn(List.of(overallA, overallB));
        when(aiService.generateEmbedding(anyString())).thenThrow(new RuntimeException("embedding unavailable"));

        List<Map<String, Object>> groups = service.buildGroups(1L, start, end, 10);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).get("module")).isEqualTo("game overall");
        assertThat(groups.get(0).get("count")).isEqualTo(2);
        verify(aiService, times(1)).generateEmbedding(anyString());
    }

    private FeedbackClaim praise(String id,
                                 String rawId,
                                 String module,
                                 String summary,
                                 String content,
                                 String keywords,
                                 String embeddingVector,
                                 LocalDateTime createdAt) {
        return FeedbackClaim.builder()
                .id(id)
                .rawId(rawId)
                .analyzedId("AN-" + id)
                .productId(1L)
                .claimIndex(0)
                .primaryClaim(true)
                .category(FeedbackCategoryEnum.PRAISE)
                .module(module)
                .summary(summary)
                .content(content)
                .keywords(keywords)
                .embeddingVector(embeddingVector)
                .status(FeedbackClaimStatusEnum.RECORDED)
                .createdAt(createdAt)
                .updatedAt(createdAt.plusMinutes(1))
                .build();
    }
}
