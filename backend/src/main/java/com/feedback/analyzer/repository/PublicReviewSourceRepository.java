package com.feedback.analyzer.repository;

import com.feedback.analyzer.entity.PublicReviewSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PublicReviewSourceRepository extends JpaRepository<PublicReviewSource, Long> {

    List<PublicReviewSource> findByProductIdOrderByCreatedAtDesc(Long productId);

    List<PublicReviewSource> findByEnabledTrueAndScheduledEnabledTrueAndInitializedTrue();

    boolean existsByProductIdAndPlatformAndAppId(Long productId, String platform, String appId);

    boolean existsByProductIdAndPlatformAndAppIdAndIdNot(Long productId, String platform, String appId, Long id);
}
