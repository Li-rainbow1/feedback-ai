package com.feedback.analyzer.controller;

import com.feedback.analyzer.entity.PublicReviewCollectRun;
import com.feedback.analyzer.entity.PublicReviewSource;
import com.feedback.analyzer.model.ApiResult;
import com.feedback.analyzer.model.dto.PublicReviewItem;
import com.feedback.analyzer.service.PublicReviewSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/public-review-sources")
@RequiredArgsConstructor
public class PublicReviewSourceController {

    private final PublicReviewSourceService sourceService;

    @GetMapping
    public ApiResult<List<PublicReviewSource>> list(@RequestParam Long productId) {
        return ApiResult.success(sourceService.list(productId));
    }

    @GetMapping("/{id}")
    public ApiResult<PublicReviewSource> get(@PathVariable Long id) {
        return ApiResult.success(sourceService.get(id));
    }

    @PostMapping
    public ApiResult<PublicReviewSource> create(@RequestBody PublicReviewSource source) {
        return ApiResult.success(sourceService.create(source));
    }

    @PutMapping("/{id}")
    public ApiResult<PublicReviewSource> update(@PathVariable Long id, @RequestBody PublicReviewSource source) {
        return ApiResult.success(sourceService.update(id, source));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        sourceService.delete(id);
        return ApiResult.success();
    }

    @PostMapping("/{id}/preview")
    public ApiResult<List<PublicReviewItem>> preview(@PathVariable Long id) {
        return ApiResult.success(sourceService.preview(id));
    }

    @PostMapping("/{id}/initialize")
    public ApiResult<PublicReviewCollectRun> initialize(@PathVariable Long id) {
        return ApiResult.success(sourceService.initialize(id));
    }

    @PostMapping("/{id}/collect")
    public ApiResult<PublicReviewCollectRun> collect(@PathVariable Long id) {
        return ApiResult.success(sourceService.collect(id, "MANUAL"));
    }

    @GetMapping("/{id}/runs")
    public ApiResult<List<PublicReviewCollectRun>> runs(@PathVariable Long id) {
        return ApiResult.success(sourceService.runs(id));
    }
}
