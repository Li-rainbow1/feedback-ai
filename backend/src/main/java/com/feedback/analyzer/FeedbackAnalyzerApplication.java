package com.feedback.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FeedbackAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeedbackAnalyzerApplication.class, args);
    }
}
