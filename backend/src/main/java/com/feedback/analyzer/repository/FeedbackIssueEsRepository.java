package com.feedback.analyzer.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feedback.analyzer.entity.FeedbackIssueDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.*;
import org.springframework.stereotype.Repository;

import java.io.InputStream;
import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class FeedbackIssueEsRepository {

    private final ElasticsearchOperations esOps;
    private final ElasticsearchClient esClient;
    private final ObjectMapper objectMapper;

    public FeedbackIssueEsRepository(ElasticsearchOperations esOps, ElasticsearchClient esClient, ObjectMapper objectMapper) {
        this.esOps = esOps;
        this.esClient = esClient;
        this.objectMapper = objectMapper;
    }

    public void save(FeedbackIssueDocument doc) {
        IndexQuery indexQuery = new IndexQuery();
        indexQuery.setId(doc.getIssueId());
        indexQuery.setObject(doc);
        esOps.index(indexQuery, esOps.getIndexCoordinatesFor(FeedbackIssueDocument.class));
    }

    public List<FeedbackIssueDocument> findByProductId(Long productId, int maxResults) {
        try {
            var criteria = org.springframework.data.elasticsearch.core.query.Criteria
                    .where("productId").is(productId);
            var query = new CriteriaQuery(criteria);
            query.setMaxResults(maxResults);
            var hits = esOps.search(query, FeedbackIssueDocument.class);
            return hits.getSearchHits().stream()
                    .map(org.springframework.data.elasticsearch.core.SearchHit::getContent)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<FeedbackIssueDocument> knnSearch(double[] queryVector, Long productId, int k, float minScore) {
        return knnSearchWithScore(queryVector, productId, k, minScore).stream()
                .map(ScoredIssueDocument::document)
                .collect(Collectors.toList());
    }

    public List<ScoredIssueDocument> knnSearchWithScore(double[] queryVector, Long productId, int k, float minScore) {
        return knnSearchWithScore(queryVector, productId, null, k, minScore);
    }

    public List<ScoredIssueDocument> knnSearchWithScore(double[] queryVector, Long productId, String category, int k, float minScore) {
        String indexName = esOps.getIndexCoordinatesFor(FeedbackIssueDocument.class).getIndexName();
        try {
            KnnQuery knn = KnnQuery.of(kq -> kq
                    .field("embedding")
                    .queryVector(toFloatList(queryVector))
                    .k(k)
                    .numCandidates(k * 3));

            Query filter = Query.of(q -> q.bool(b -> {
                b.filter(m -> m.term(t -> t.field("productId").value(productId)));
                if (category != null && !category.isBlank()) {
                    b.filter(m -> m.term(t -> t.field("category").value(category)));
                }
                return b;
            }));

            SearchResponse<FeedbackIssueDocument> response = esClient.search(
                    s -> s.index(indexName).knn(knn).query(filter).minScore((double) minScore).size(k),
                    FeedbackIssueDocument.class);

            return response.hits().hits().stream()
                    .filter(h -> h.score() != null && h.score() >= minScore)
                    .filter(h -> h.source() != null)
                    .map(h -> new ScoredIssueDocument(h.source(), h.score()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("KNN search failed on index '{}': {}", indexName, e.getMessage(), e);
            return List.of();
        }
    }

    public void deleteByIssueId(String issueId) {
        esOps.delete(issueId, FeedbackIssueDocument.class);
    }

    public String getIndexName() {
        return esOps.getIndexCoordinatesFor(FeedbackIssueDocument.class).getIndexName();
    }

    public boolean ensureIndex() {
        try {
            String indexName = getIndexName();
            boolean exists = esClient.indices().exists(r -> r.index(indexName)).value();
            if (exists) {
                return false;
            }

            Map<String, Object> settings;
            Map<String, Object> mappingRoot;
            try (InputStream settingsIs = new ClassPathResource("es/feedback-issue-setting.json").getInputStream();
                 InputStream mappingIs = new ClassPathResource("es/feedback-issue-mapping.json").getInputStream()) {
                settings = objectMapper.readValue(settingsIs, Map.class);
                mappingRoot = objectMapper.readValue(mappingIs, Map.class);
            }

            String settingsJson = objectMapper.writeValueAsString(settings);
            String mappingJson = objectMapper.writeValueAsString(mappingRoot);

            esClient.indices().create(c -> c
                    .index(indexName)
                    .settings(s -> s.withJson(new StringReader(settingsJson)))
                    .mappings(m -> m.withJson(new StringReader(mappingJson))));
            log.info("Created Elasticsearch index '{}'", indexName);
            return true;
        } catch (Exception e) {
            log.error("Failed to ensure Elasticsearch index", e);
            throw new RuntimeException("Failed to ensure Elasticsearch index", e);
        }
    }

    public long count() {
        try {
            String indexName = getIndexName();
            boolean exists = esClient.indices().exists(r -> r.index(indexName)).value();
            if (!exists) {
                return 0;
            }
            return esClient.count(c -> c.index(indexName)).count();
        } catch (Exception e) {
            log.warn("Failed to count ES issue documents: {}", e.getMessage());
            return 0;
        }
    }

    private List<Float> toFloatList(double[] arr) {
        List<Float> result = new ArrayList<>(arr.length);
        for (double v : arr) result.add((float) v);
        return result;
    }

    public record ScoredIssueDocument(FeedbackIssueDocument document, double score) {
    }
}
