package com.feedback.analyzer.service;

import com.feedback.analyzer.entity.FeedbackIssue;
import com.feedback.analyzer.model.dto.ZenTaoBugSnapshot;
import com.feedback.analyzer.model.enums.IssueStatusEnum;

public interface ZenTaoService {

    String createBug(FeedbackIssue issue);

    String createBug(FeedbackIssue issue, String title, String description);

    boolean syncBugUpdate(FeedbackIssue issue);

    boolean syncBugUpdate(FeedbackIssue issue, String comment);

    boolean updateBugTriage(FeedbackIssue issue);

    boolean updateBugStatus(FeedbackIssue issue, IssueStatusEnum status);

    boolean confirmBug(FeedbackIssue issue);

    void validateBugLink(FeedbackIssue issue, String issueKey);

    ZenTaoBugSnapshot fetchBugSnapshot(String issueKey);
}
