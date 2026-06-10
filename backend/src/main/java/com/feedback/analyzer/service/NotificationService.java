package com.feedback.analyzer.service;

public interface NotificationService {

    boolean notifyReportReady(Long productId, Long reportId, String weekRange, String content);
}
