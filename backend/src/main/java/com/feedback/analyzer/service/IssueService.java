package com.feedback.analyzer.service;

import com.feedback.analyzer.model.enums.IssueStatusEnum;
import com.feedback.analyzer.model.vo.IssueListItemVO;
import com.feedback.analyzer.model.vo.IssueVO;
import org.springframework.data.domain.Page;

public interface IssueService {

    Page<IssueListItemVO> search(Long productId, String severity, IssueStatusEnum status, String category, String module, int page, int size);

    IssueVO getDetail(String issueId);

    void updateStatus(String issueId, IssueStatusEnum status);

    void confirmIssue(String issueId);

    void updateCategory(String issueId, String category);

    void updateTriage(String issueId, String severity, String priority, String reason);

    void linkIssue(String issueId, String issueKey);

    void mergeIssue(String sourceIssueId, String targetIssueId);

    void reassignClaim(String claimId, String targetIssueId);
}
