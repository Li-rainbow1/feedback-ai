package com.feedback.analyzer.service;

import com.feedback.analyzer.entity.FeedbackAnalyzed;
import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.model.dto.FeedbackQueryRequest;
import com.feedback.analyzer.model.dto.FeedbackSubmitRequest;
import org.springframework.data.domain.Page;

import java.util.List;

public interface FeedbackService {

    FeedbackRaw submit(Long productId, FeedbackSubmitRequest request);

    FeedbackRaw submitSync(Long productId, FeedbackSubmitRequest request);

    void processRaw(String rawId);

    void retryTimedOutAnalyzing(String rawId);

    void batchProcessRaw(List<String> rawIds);

    FeedbackRaw getRawStatus(String rawId);

    FeedbackAnalyzed getAnalyzed(String id);

    Page<FeedbackAnalyzed> search(FeedbackQueryRequest request);

    Page<FeedbackAnalyzed> getByIssueId(String issueId, int page, int size);

    int reindexAllIssues();
}
