package com.feedback.analyzer.service;

import com.feedback.analyzer.model.vo.WeeklyReportVO;

import java.util.List;

public interface WeeklyReportService {

    WeeklyReportVO generate(Long productId, String weekStart);

    List<WeeklyReportVO> list(Long productId);

    WeeklyReportVO getById(Long id);

    void send(Long id);
}
