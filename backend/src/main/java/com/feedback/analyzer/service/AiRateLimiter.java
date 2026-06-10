package com.feedback.analyzer.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiRateLimiter {

    private final RedissonClient redisson;

    @Value("${ai.rate-limit.llm.rate:5}")
    private long llmRate;

    @Value("${ai.rate-limit.embedding.rate:10}")
    private long embeddingRate;

    private RRateLimiter llmLimiter;
    private RRateLimiter embeddingLimiter;

    @PostConstruct
    public void init() {
        llmLimiter = redisson.getRateLimiter("ai:rate-limit:llm");
        llmLimiter.trySetRate(RateType.OVERALL, Math.max(1, llmRate), 1, RateIntervalUnit.SECONDS);
        embeddingLimiter = redisson.getRateLimiter("ai:rate-limit:embedding");
        embeddingLimiter.trySetRate(RateType.OVERALL, Math.max(1, embeddingRate), 1, RateIntervalUnit.SECONDS);
    }

    public void acquireLlm() {
        llmLimiter.acquire();
    }

    public void acquireEmbedding() {
        embeddingLimiter.acquire();
    }
}
