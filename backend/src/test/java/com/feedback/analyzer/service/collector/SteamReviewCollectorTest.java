package com.feedback.analyzer.service.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.model.dto.PublicReviewItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SteamReviewCollectorTest {

    @Test
    void parsesPublicReviewFields() {
        String json = """
                {"cursor":"next","reviews":[{
                  "recommendationid":"9988",
                  "review":"启动后黑屏闪退",
                  "voted_up":false,
                  "timestamp_created":1779667200,
                  "votes_up":12,
                  "weighted_vote_score":"0.9",
                  "author":{"steamid":"7656","playtime_forever":130}
                }]}
                """;

        SteamReviewCollector.SteamPage page = new SteamReviewCollector(new ObjectMapper()).parse(json);
        List<PublicReviewItem> result = page.items();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).externalId()).isEqualTo("9988");
        assertThat(result.get(0).star()).isNull();
        assertThat(result.get(0).metadata())
                .containsEntry("platform", "Steam")
                .containsEntry("recommended", false)
                .containsEntry("playtimeMinutes", 130);
    }
}
