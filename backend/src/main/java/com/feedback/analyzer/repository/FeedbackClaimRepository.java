package com.feedback.analyzer.repository;

import com.feedback.analyzer.entity.FeedbackClaim;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import com.feedback.analyzer.model.enums.FeedbackClaimStatusEnum;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FeedbackClaimRepository extends JpaRepository<FeedbackClaim, String> {

    List<FeedbackClaim> findByAnalyzedIdOrderByClaimIndexAsc(String analyzedId);

    List<FeedbackClaim> findByRawIdOrderByClaimIndexAsc(String rawId);

    List<FeedbackClaim> findByRawIdInOrderByRawIdAscClaimIndexAsc(List<String> rawIds);

    List<FeedbackClaim> findByIssueId(String issueId);

    Page<FeedbackClaim> findByIssueId(String issueId, Pageable pageable);

    long countByIssueId(String issueId);

    @Query("SELECT COUNT(DISTINCT c.rawId) FROM FeedbackClaim c WHERE " +
            "c.issueId = :issueId AND c.createdAt BETWEEN :start AND :end")
    long countDistinctRawIdByIssueIdAndCreatedAtBetween(@Param("issueId") String issueId,
                                                        @Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end);

    long countByProductIdAndCategoryAndCreatedAtBetween(Long productId,
                                                        FeedbackCategoryEnum category,
                                                        LocalDateTime start,
                                                        LocalDateTime end);

    long countByProductIdAndCreatedAtBetween(Long productId,
                                             LocalDateTime start,
                                             LocalDateTime end);

    long countByProductIdAndCategoryAndStatusAndCreatedAtBetween(Long productId,
                                                                 FeedbackCategoryEnum category,
                                                                 FeedbackClaimStatusEnum status,
                                                                 LocalDateTime start,
                                                                 LocalDateTime end);

    @Query("SELECT c.module, COUNT(c) FROM FeedbackClaim c WHERE " +
            "c.productId = :productId AND c.category = :category AND " +
            "c.createdAt BETWEEN :start AND :end " +
            "GROUP BY c.module ORDER BY COUNT(c) DESC")
    List<Object[]> countModulesByCategory(@Param("productId") Long productId,
                                          @Param("category") FeedbackCategoryEnum category,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

    @Query("SELECT c.module, COUNT(c) FROM FeedbackClaim c WHERE " +
            "c.productId = :productId AND c.category = :category AND c.status = :status AND " +
            "c.createdAt BETWEEN :start AND :end " +
            "GROUP BY c.module ORDER BY COUNT(c) DESC")
    List<Object[]> countModulesByCategoryAndStatus(@Param("productId") Long productId,
                                                   @Param("category") FeedbackCategoryEnum category,
                                                   @Param("status") FeedbackClaimStatusEnum status,
                                                   @Param("start") LocalDateTime start,
                                                   @Param("end") LocalDateTime end);

    @Query("SELECT c.issueId, COUNT(DISTINCT c.rawId) FROM FeedbackClaim c, FeedbackIssue i WHERE " +
            "c.issueId = i.id AND c.productId = :productId AND c.category = :category AND c.issueId IS NOT NULL AND " +
            "i.productId = :productId AND i.category = :category AND i.status NOT IN :excludedStatuses AND " +
            "i.latestReportAt BETWEEN :start AND :end AND c.createdAt BETWEEN :start AND :end " +
            "GROUP BY c.issueId ORDER BY COUNT(DISTINCT c.rawId) DESC")
    List<Object[]> countTopIssueIdsByCategory(@Param("productId") Long productId,
                                              @Param("category") FeedbackCategoryEnum category,
                                              @Param("excludedStatuses") List<IssueStatusEnum> excludedStatuses,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    @Query("SELECT c.issueId, COUNT(DISTINCT c.rawId) FROM FeedbackClaim c, FeedbackIssue i WHERE " +
            "c.issueId = i.id AND c.productId = :productId AND c.category = :category AND c.issueId IS NOT NULL AND " +
            "i.productId = :productId AND i.category = :category AND i.status NOT IN :excludedStatuses AND " +
            "i.severity IN :severities AND i.latestReportAt BETWEEN :start AND :end AND c.createdAt BETWEEN :start AND :end " +
            "GROUP BY c.issueId ORDER BY COUNT(DISTINCT c.rawId) DESC")
    List<Object[]> countUrgentIssueIdsByCategory(@Param("productId") Long productId,
                                                 @Param("category") FeedbackCategoryEnum category,
                                                 @Param("severities") List<String> severities,
                                                 @Param("excludedStatuses") List<IssueStatusEnum> excludedStatuses,
                                                 @Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end);

    Page<FeedbackClaim> findByProductIdAndCategoryAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long productId,
            FeedbackCategoryEnum category,
            FeedbackClaimStatusEnum status,
            LocalDateTime start,
            LocalDateTime end,
            Pageable pageable);

    List<FeedbackClaim> findByProductIdAndCategoryAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long productId,
            FeedbackCategoryEnum category,
            FeedbackClaimStatusEnum status,
            LocalDateTime start,
            LocalDateTime end);
}
