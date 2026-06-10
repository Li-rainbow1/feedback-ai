package com.feedback.analyzer.controller;

import com.feedback.analyzer.model.ApiResult;
import com.feedback.analyzer.model.dto.IssueTriageUpdateRequest;
import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.model.vo.IssueListItemVO;
import com.feedback.analyzer.model.vo.IssueVO;
import com.feedback.analyzer.service.IssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;

    @GetMapping
    public ApiResult<Page<IssueListItemVO>> list(
            @RequestParam Long productId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String module,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.success(issueService.search(productId, severity, IssueStatusEnum.parse(status), category, module, page, size));
    }

    @GetMapping("/{id}")
    public ApiResult<IssueVO> detail(@PathVariable String id) {
        return ApiResult.success(issueService.getDetail(id));
    }

    @PatchMapping("/{id}/status")
    public ApiResult<Void> updateStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        IssueStatusEnum status = IssueStatusEnum.parse(body.get("status"));
        issueService.updateStatus(id, status);
        return ApiResult.success();
    }

    @PatchMapping("/{id}/confirm")
    public ApiResult<Void> confirmIssue(@PathVariable String id) {
        issueService.confirmIssue(id);
        return ApiResult.success();
    }

    @PatchMapping("/{id}/category")
    public ApiResult<Void> updateCategory(@PathVariable String id, @RequestBody Map<String, String> body) {
        issueService.updateCategory(id, body.get("category"));
        return ApiResult.success();
    }

    @PatchMapping("/{id}/triage")
    public ApiResult<Void> updateTriage(@PathVariable String id, @RequestBody IssueTriageUpdateRequest body) {
        issueService.updateTriage(id, body.getSeverity(), body.getPriority(), body.getReason());
        return ApiResult.success();
    }

    @PostMapping("/{id}/link-issue")
    public ApiResult<Void> linkIssue(@PathVariable String id, @RequestBody Map<String, String> body) {
        issueService.linkIssue(id, body.get("issueKey"));
        return ApiResult.success();
    }

    @PostMapping("/{id}/merge")
    public ApiResult<Void> mergeIssue(@PathVariable String id, @RequestBody Map<String, String> body) {
        issueService.mergeIssue(id, body.get("targetIssueId"));
        return ApiResult.success();
    }

    @PostMapping("/claims/{claimId}/reassign")
    public ApiResult<Void> reassignClaim(@PathVariable String claimId, @RequestBody Map<String, String> body) {
        issueService.reassignClaim(claimId, body.get("targetIssueId"));
        return ApiResult.success();
    }
}
