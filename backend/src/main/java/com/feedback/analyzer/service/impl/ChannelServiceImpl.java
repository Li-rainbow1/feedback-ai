package com.feedback.analyzer.service.impl;

import com.feedback.analyzer.entity.ChannelConfig;
import com.feedback.analyzer.repository.ChannelConfigRepository;
import com.feedback.analyzer.service.ChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChannelServiceImpl implements ChannelService {

    private final ChannelConfigRepository channelRepo;

    @Override
    public ChannelConfig create(ChannelConfig config) {
        normalize(config);
        if (channelRepo.existsByName(config.getName())) {
            throw new RuntimeException("渠道名称已存在: " + config.getName());
        }
        validate(config, null);
        return channelRepo.save(config);
    }

    @Override
    public ChannelConfig update(Long id, ChannelConfig config) {
        ChannelConfig existing = channelRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Channel not found: " + id));
        normalize(config);
        channelRepo.findByName(config.getName())
                .filter(channel -> !channel.getId().equals(id))
                .ifPresent(channel -> {
                    throw new RuntimeException("渠道名称已存在: " + config.getName());
                });
        validate(config, id);

        existing.setName(config.getName());
        existing.setType(config.getType());
        existing.setSourceType(config.getSourceType());
        existing.setSourceKey(config.getSourceKey());
        existing.setCredentials(config.getCredentials());
        existing.setEnabled(config.getEnabled());
        return channelRepo.save(existing);
    }

    @Override
    public void delete(Long id) {
        channelRepo.deleteById(id);
    }

    @Override
    public ChannelConfig get(Long id) {
        return channelRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Channel not found: " + id));
    }

    @Override
    public List<ChannelConfig> listByProductId(Long productId) {
        return channelRepo.findByProductIdAndType(productId, "push");
    }

    @Override
    public List<ChannelConfig> list() {
        return channelRepo.findAll().stream()
                .filter(channel -> "push".equals(channel.getType()) || "webhook".equals(channel.getType()))
                .toList();
    }

    private void normalize(ChannelConfig config) {
        if ("webhook".equals(config.getType())) {
            config.setType("push");
        }
        if (config.getType() == null || config.getType().isBlank()) {
            config.setType("push");
        }
        config.setSourceType("webhook");
        config.setCredentials("{\"sourceType\":\"webhook\"}");
    }

    private void validate(ChannelConfig config, Long id) {
        if (!List.of("push", "webhook").contains(config.getType())) {
            throw new RuntimeException("不支持的接入方式: " + config.getType());
        }
        config.setType("push");
        config.setSourceType("webhook");
        if (config.getSourceKey() != null && !config.getSourceKey().isBlank()) {
            boolean exists = id == null
                    ? channelRepo.existsByProductIdAndSourceKey(config.getProductId(), config.getSourceKey())
                    : channelRepo.existsByProductIdAndSourceKeyAndIdNot(config.getProductId(), config.getSourceKey(), id);
            if (exists) {
                throw new RuntimeException("该来源已存在渠道配置: " + config.getSourceKey());
            }
        }
    }
}
