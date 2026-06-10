package com.feedback.analyzer.repository;

import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.model.enums.FeedbackStatusEnum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FeedbackRawRepository extends JpaRepository<FeedbackRaw, String> {

    List<FeedbackRaw> findByStatusOrderByCreatedAtAsc(FeedbackStatusEnum status, org.springframework.data.domain.Pageable pageable);

    List<FeedbackRaw> findByProductId(Long productId);

    @Query("SELECT r FROM FeedbackRaw r WHERE " +
            "r.productId = :productId AND " +
            "(:keyword IS NULL OR r.rawContent LIKE %:keyword% OR r.userName LIKE %:keyword% OR r.userId LIKE %:keyword%) " +
            "ORDER BY r.createdAt DESC")
    Page<FeedbackRaw> searchRawFeedbacks(@Param("productId") Long productId,
                                         @Param("keyword") String keyword,
                                         Pageable pageable);

    boolean existsByChannelIdAndExternalReviewId(Long channelId, String externalReviewId);

    boolean existsByPublicReviewSourceIdAndExternalReviewId(Long publicReviewSourceId, String externalReviewId);

    long countByPublicReviewSourceId(Long publicReviewSourceId);

    List<FeedbackRaw> findByCollectionRunId(Long collectionRunId);

    long countByProductId(Long productId);

    long countByProductIdAndCreatedAtBetween(Long productId, LocalDateTime start, LocalDateTime end);

    long countByProductIdAndStatusAndCreatedAtBetween(Long productId,
                                                      FeedbackStatusEnum status,
                                                      LocalDateTime start,
                                                      LocalDateTime end);

    long countByProductIdAndStatusInAndCreatedAtBetween(Long productId,
                                                        List<FeedbackStatusEnum> statuses,
                                                        LocalDateTime start,
                                                        LocalDateTime end);

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    @Query("SELECT r.channel, COUNT(r) FROM FeedbackRaw r WHERE r.productId = :productId AND r.createdAt BETWEEN :start AND :end GROUP BY r.channel")
    List<Object[]> countGroupByChannel(@Param("productId") Long productId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT r.channel, COUNT(r) FROM FeedbackRaw r WHERE r.createdAt BETWEEN :start AND :end GROUP BY r.channel")
    List<Object[]> countGroupByChannel(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT r FROM FeedbackRaw r WHERE r.status = :status AND r.createdAt < :before")
    List<FeedbackRaw> findStuckRecords(@Param("status") FeedbackStatusEnum status, @Param("before") LocalDateTime before);

    List<FeedbackRaw> findByStatusAndProcessingStartedAtBefore(FeedbackStatusEnum status, LocalDateTime before);
}
