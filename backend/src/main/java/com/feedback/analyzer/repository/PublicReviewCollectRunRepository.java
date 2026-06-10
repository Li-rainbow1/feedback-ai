package com.feedback.analyzer.repository;

import com.feedback.analyzer.entity.PublicReviewCollectRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Collection;

@Repository
public interface PublicReviewCollectRunRepository extends JpaRepository<PublicReviewCollectRun, Long> {

    List<PublicReviewCollectRun> findBySourceIdOrderByStartedAtDesc(Long sourceId);

    boolean existsBySourceIdAndStatus(Long sourceId, String status);

    List<PublicReviewCollectRun> findByStatusAndStartedAtBefore(String status, LocalDateTime startedAt);

    boolean existsBySourceIdAndStatusIn(Long sourceId, Collection<String> statuses);

    List<PublicReviewCollectRun> findByStatusInAndStartedAtBefore(Collection<String> statuses, LocalDateTime startedAt);

    List<PublicReviewCollectRun> findBySourceIdAndStatusInOrderByStartedAtDesc(Long sourceId, Collection<String> statuses);

    List<PublicReviewCollectRun> findByStatusOrderByStartedAtAsc(String status, Pageable pageable);
}
