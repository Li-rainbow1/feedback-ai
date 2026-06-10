package com.feedback.analyzer.repository;

import com.feedback.analyzer.entity.ChannelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChannelConfigRepository extends JpaRepository<ChannelConfig, Long> {

    Optional<ChannelConfig> findByName(String name);

    List<ChannelConfig> findByProductId(Long productId);

    List<ChannelConfig> findByProductIdAndType(Long productId, String type);

    Optional<ChannelConfig> findByProductIdAndSourceKeyAndSourceTypeAndEnabledTrue(Long productId,
                                                                                    String sourceKey,
                                                                                    String sourceType);

    boolean existsByName(String name);

    boolean existsByProductIdAndSourceKey(Long productId, String sourceKey);

    boolean existsByProductIdAndSourceKeyAndIdNot(Long productId, String sourceKey, Long id);
}
