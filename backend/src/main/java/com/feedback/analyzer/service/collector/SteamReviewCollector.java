package com.feedback.analyzer.service.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.PublicReviewSource;
import com.feedback.analyzer.model.dto.PublicReviewItem;
import com.feedback.analyzer.service.PublicReviewCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SteamReviewCollector implements PublicReviewCollector {

    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.builder()
            .requestFactory(requestFactory())
            .defaultHeader("User-" + "A" + "gent", "Mozilla/5.0 FeedbackAnalyzer/1.0")
            .build();

    @Override
    public boolean supports(String platform) {
        return "STEAM".equalsIgnoreCase(platform);
    }

    @Override
    public List<PublicReviewItem> collect(PublicReviewSource source, int limit) {
        List<PublicReviewItem> items = new ArrayList<>();
        String cursor = "*";
        String language = source.getLanguage() == null || source.getLanguage().isBlank()
                ? "schinese" : source.getLanguage();
        while (items.size() < limit) {
            int pageSize = Math.min(100, limit - items.size());
            String url = "https://store.steampowered.com/appreviews/" + source.getAppId()
                    + "?json=1&language=" + language
                    + "&filter=recent&review_type=all&purchase_type=all&num_per_page=" + pageSize
                    + "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
            String body = fetchWithRetry(url);
            SteamPage page = parse(body);
            if (page.items().isEmpty()) {
                break;
            }
            items.addAll(page.items());
            if (page.cursor().isBlank() || page.cursor().equals(cursor)) {
                break;
            }
            cursor = page.cursor();
        }
        return items.stream().limit(limit).toList();
    }

    private String fetchWithRetry(String url) {
        RuntimeException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                return restClient.get().uri(url).retrieve().body(String.class);
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(500L * (i + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Steam 采集请求被中断", interrupted);
                }
            }
        }
        throw last;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(8));
        factory.setReadTimeout(Duration.ofSeconds(12));
        return factory;
    }

    SteamPage parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<PublicReviewItem> result = new ArrayList<>();
            for (JsonNode review : root.path("reviews")) {
                String externalId = review.path("recommendationid").asText("").trim();
                String content = review.path("review").asText("").trim();
                if (externalId.isBlank() || content.isBlank()) {
                    continue;
                }
                JsonNode author = review.path("author");
                String userId = author.path("steamid").asText("");
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("platform", "Steam");
                metadata.put("recommended", review.path("voted_up").asBoolean());
                metadata.put("playtimeMinutes", author.path("playtime_forever").asInt());
                metadata.put("votesUp", review.path("votes_up").asInt());
                metadata.put("weightedScore", review.path("weighted_vote_score").asText(""));
                result.add(new PublicReviewItem(
                        externalId,
                        content,
                        userId,
                        userId.isBlank() ? "Steam 用户" : "Steam 用户 " + userId,
                        null,
                        null,
                        null,
                        LocalDateTime.ofInstant(
                                Instant.ofEpochSecond(review.path("timestamp_created").asLong()),
                                ZoneId.of("Asia/Shanghai")),
                        metadata
                ));
            }
            return new SteamPage(root.path("cursor").asText(""), result);
        } catch (Exception e) {
            throw new IllegalStateException("Steam 评论解析失败: " + e.getMessage(), e);
        }
    }

    record SteamPage(String cursor, List<PublicReviewItem> items) {
    }
}
