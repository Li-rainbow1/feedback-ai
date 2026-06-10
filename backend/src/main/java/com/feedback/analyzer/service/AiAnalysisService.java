package com.feedback.analyzer.service;

import com.feedback.analyzer.model.dto.AiAnalysisResult;

import java.util.List;
import java.util.Map;

public interface AiAnalysisService {

    List<AiAnalysisResult> batchAnalyze(List<Map<String, String>> feedbackBatch);

    List<Double> generateEmbedding(String text);
}
