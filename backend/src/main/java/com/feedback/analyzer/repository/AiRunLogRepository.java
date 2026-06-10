package com.feedback.analyzer.repository;

import com.feedback.analyzer.entity.AiRunLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiRunLogRepository extends JpaRepository<AiRunLog, Long> {

    @Query("SELECT l FROM AiRunLog l WHERE " +
            "(:productId IS NULL OR l.productId = :productId) AND " +
            "(:aiType IS NULL OR l.aiType = :aiType) AND " +
            "(:status IS NULL OR l.status = :status) " +
            "ORDER BY l.createdAt DESC")
    Page<AiRunLog> search(@Param("productId") Long productId,
                             @Param("aiType") String aiType,
                             @Param("status") String status,
                             Pageable pageable);

    Optional<AiRunLog> findTopByAiTypeAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
            String aiType, String targetType, String targetId);
}
