package com.feedback.analyzer.controller;

import com.feedback.analyzer.model.ApiResult;
import com.feedback.analyzer.model.vo.WeeklyReportVO;
import com.feedback.analyzer.service.WeeklyReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class WeeklyReportController {

    private final WeeklyReportService weeklyReportService;

    @PostMapping("/weekly/generate")
    public ApiResult<WeeklyReportVO> generate(@RequestParam Long productId, @RequestParam(required = false) String weekStart) {
        return ApiResult.success(weeklyReportService.generate(productId, weekStart));
    }

    @GetMapping("/weekly")
    public ApiResult<List<WeeklyReportVO>> list(@RequestParam Long productId) {
        return ApiResult.success(weeklyReportService.list(productId));
    }

    @GetMapping("/weekly/{id}")
    public ApiResult<WeeklyReportVO> getById(@PathVariable Long id) {
        return ApiResult.success(weeklyReportService.getById(id));
    }

    @PostMapping("/weekly/{id}/send")
    public ApiResult<Void> send(@PathVariable Long id) {
        weeklyReportService.send(id);
        return ApiResult.success();
    }
}
