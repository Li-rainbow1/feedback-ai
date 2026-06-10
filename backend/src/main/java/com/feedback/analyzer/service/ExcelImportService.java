package com.feedback.analyzer.service;

import org.springframework.web.multipart.MultipartFile;

public interface ExcelImportService {

    int importFromExcel(Long productId, String channel, MultipartFile file);
}
