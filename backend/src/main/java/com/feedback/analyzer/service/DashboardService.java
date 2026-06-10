package com.feedback.analyzer.service;

import com.feedback.analyzer.model.vo.DashboardVO;

import java.time.LocalDateTime;

public interface DashboardService {

    DashboardVO getOverview(Long productId, LocalDateTime start, LocalDateTime end);
}
