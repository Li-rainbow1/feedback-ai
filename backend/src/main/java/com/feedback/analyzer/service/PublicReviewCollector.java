package com.feedback.analyzer.service;

import com.feedback.analyzer.entity.PublicReviewSource;
import com.feedback.analyzer.model.dto.PublicReviewItem;

import java.util.List;

public interface PublicReviewCollector {

    boolean supports(String platform);

    List<PublicReviewItem> collect(PublicReviewSource source, int limit);
}
