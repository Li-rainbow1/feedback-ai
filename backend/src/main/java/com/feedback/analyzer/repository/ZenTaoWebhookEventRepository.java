package com.feedback.analyzer.repository;

import com.feedback.analyzer.entity.ZenTaoWebhookEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface ZenTaoWebhookEventRepository extends JpaRepository<ZenTaoWebhookEvent, Long> {

    List<ZenTaoWebhookEvent> findByStatusInAndNextRetryAtLessThanEqualOrderByReceivedAtAsc(
            Collection<String> statuses,
            LocalDateTime nextRetryAt,
            Pageable pageable);

    List<ZenTaoWebhookEvent> findByStatusAndUpdatedAtBefore(
            String status,
            LocalDateTime updatedAt,
            Pageable pageable);
}
