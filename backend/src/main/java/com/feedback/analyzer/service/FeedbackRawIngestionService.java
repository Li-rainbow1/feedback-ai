package com.feedback.analyzer.service;

import com.feedback.analyzer.entity.FeedbackRaw;
import com.feedback.analyzer.repository.FeedbackRawRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackRawIngestionService {

    private final FeedbackRawRepository rawRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${spring.kafka.topics.feedback-raw}")
    private String kafkaTopicRaw;

    public FeedbackRaw save(FeedbackRaw raw) {
        return rawRepo.save(raw);
    }

    public FeedbackRaw saveAndFlush(FeedbackRaw raw) {
        return rawRepo.saveAndFlush(raw);
    }

    public FeedbackRaw saveAndPublish(FeedbackRaw raw) {
        FeedbackRaw saved = save(raw);
        publish(saved);
        return saved;
    }

    public void publish(FeedbackRaw raw) {
        kafkaTemplate.send(kafkaTopicRaw, raw.getId(), raw).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to send feedback raw to Kafka: {}", raw.getId(), ex);
            }
        });
    }
}
