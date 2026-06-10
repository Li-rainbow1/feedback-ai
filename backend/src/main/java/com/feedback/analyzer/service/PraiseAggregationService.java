package com.feedback.analyzer.service;

import com.feedback.analyzer.entity.FeedbackClaim;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface PraiseAggregationService {

    List<Map<String, Object>> buildGroups(Long productId,
                                          LocalDateTime start,
                                          LocalDateTime end,
                                          int limit);

    List<FeedbackClaim> getGroupClaims(Long productId,
                                       LocalDateTime start,
                                       LocalDateTime end,
                                       String groupId);
}
