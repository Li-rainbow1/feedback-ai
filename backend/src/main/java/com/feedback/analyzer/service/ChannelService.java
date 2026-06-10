package com.feedback.analyzer.service;

import com.feedback.analyzer.entity.ChannelConfig;

import java.util.List;

public interface ChannelService {

    ChannelConfig create(ChannelConfig config);

    ChannelConfig update(Long id, ChannelConfig config);

    void delete(Long id);

    ChannelConfig get(Long id);

    List<ChannelConfig> listByProductId(Long productId);

    List<ChannelConfig> list();
}
