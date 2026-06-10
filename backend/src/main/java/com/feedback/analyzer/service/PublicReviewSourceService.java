package com.feedback.analyzer.service;

import com.feedback.analyzer.entity.PublicReviewCollectRun;
import com.feedback.analyzer.entity.PublicReviewSource;
import com.feedback.analyzer.model.dto.PublicReviewItem;

import java.util.List;

public interface PublicReviewSourceService {

    List<PublicReviewSource> list(Long productId);

    PublicReviewSource get(Long id);

    PublicReviewSource create(PublicReviewSource source);

    PublicReviewSource update(Long id, PublicReviewSource source);

    void delete(Long id);

    List<PublicReviewItem> preview(Long id);

    PublicReviewCollectRun initialize(Long id);

    PublicReviewCollectRun collect(Long id, String runType);

    List<PublicReviewCollectRun> runs(Long id);

    void collectScheduled();
}
