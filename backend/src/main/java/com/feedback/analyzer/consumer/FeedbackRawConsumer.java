package com.feedback.analyzer.consumer;

import com.feedback.analyzer.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackRawConsumer {

    private final FeedbackService feedbackService;

    @KafkaListener(
        topics = "${spring.kafka.topics.feedback-raw}",
        concurrency = "3",
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        try {
            feedbackService.processRaw(record.key());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process feedback: {}", record.key(), e);
            throw e;
        }
    }
}
