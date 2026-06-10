package com.feedback.analyzer.repository;

import com.feedback.analyzer.entity.FeedbackAnalyzed;
import com.feedback.analyzer.model.enums.FeedbackCategoryEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FeedbackAnalyzedRepository extends JpaRepository<FeedbackAnalyzed, String> {

    List<FeedbackAnalyzed> findByIssueId(String issueId);

    Page<FeedbackAnalyzed> findByIssueId(String issueId, Pageable pageable);

    @Query("SELECT f FROM FeedbackAnalyzed f WHERE " +
           "f.productId = :productId AND " +
           "(:category IS NULL OR f.category = :category) AND " +
           "(:module IS NULL OR f.module = :module) AND " +
           "(:keyword IS NULL OR f.summary LIKE %:keyword% OR f.keywords LIKE %:keyword%) AND " +
           "f.analyzedAt BETWEEN :start AND :end " +
           "ORDER BY f.analyzedAt DESC")
    Page<FeedbackAnalyzed> searchFeedbacks(@Param("productId") Long productId,
                                            @Param("category") FeedbackCategoryEnum category,
                                            @Param("module") String module,
                                            @Param("keyword") String keyword,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end,
                                            Pageable pageable);

    @Query("SELECT f.category, COUNT(f) FROM FeedbackAnalyzed f WHERE f.productId = :productId AND f.analyzedAt BETWEEN :start AND :end GROUP BY f.category")
    List<Object[]> countGroupByCategory(@Param("productId") Long productId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT f.module, COUNT(f) FROM FeedbackAnalyzed f WHERE f.productId = :productId AND f.analyzedAt BETWEEN :start AND :end GROUP BY f.module")
    List<Object[]> countGroupByModule(@Param("productId") Long productId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    long countByAnalyzedAtBetween(LocalDateTime start, LocalDateTime end);

    long countByProductIdAndAnalyzedAtBetween(Long productId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT f.issueId, COUNT(f) FROM FeedbackAnalyzed f WHERE f.productId = :productId AND f.issueId IS NOT NULL AND f.analyzedAt BETWEEN :start AND :end GROUP BY f.issueId ORDER BY COUNT(f) DESC")
    List<Object[]> countTopIssueIdsByProductAndAnalyzedAtBetween(@Param("productId") Long productId,
                                                                 @Param("start") LocalDateTime start,
                                                                 @Param("end") LocalDateTime end,
                                                                 Pageable pageable);

    long countByIssueIdAndAnalyzedAtBetween(String issueId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT f FROM FeedbackAnalyzed f WHERE " +
            "f.productId = :productId AND " +
            "(:module IS NULL OR f.module = :module) AND " +
            "f.analyzedAt >= :start " +
            "ORDER BY f.analyzedAt DESC")
    List<FeedbackAnalyzed> findRecentForAi(@Param("productId") Long productId,
                                               @Param("module") String module,
                                               @Param("start") LocalDateTime start,
                                               Pageable pageable);

    List<FeedbackAnalyzed> findByRawId(String rawId);

    List<FeedbackAnalyzed> findByRawIdIn(List<String> rawIds);
}
