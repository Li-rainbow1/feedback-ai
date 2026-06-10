package com.feedback.analyzer.controller;

import com.feedback.analyzer.entity.ChannelConfig;
import com.feedback.analyzer.model.ApiResult;
import com.feedback.analyzer.service.ChannelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;

    @GetMapping
    public ApiResult<List<ChannelConfig>> list(@RequestParam(required = false) Long productId) {
        if (productId != null) {
            return ApiResult.success(channelService.listByProductId(productId));
        }
        return ApiResult.success(channelService.list());
    }

    @GetMapping("/{id}")
    public ApiResult<ChannelConfig> get(@PathVariable Long id) {
        return ApiResult.success(channelService.get(id));
    }

    @PostMapping
    public ApiResult<ChannelConfig> create(@Valid @RequestBody ChannelConfig config) {
        return ApiResult.success(channelService.create(config));
    }

    @PutMapping("/{id}")
    public ApiResult<ChannelConfig> update(@PathVariable Long id, @Valid @RequestBody ChannelConfig config) {
        return ApiResult.success(channelService.update(id, config));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        channelService.delete(id);
        return ApiResult.success();
    }
}
