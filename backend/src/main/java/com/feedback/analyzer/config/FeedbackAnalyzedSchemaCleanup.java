package com.feedback.analyzer.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackAnalyzedSchemaCleanup implements ApplicationRunner {

    private static final String TABLE_NAME = "feedback_analyzed";
    private static final String SENTIMENT_INDEX = "idx_analyzed_sentiment";
    private static final String SENTIMENT_COLUMN = "sentiment";
    private static final String SENTIMENT_SCORE_COLUMN = "sentiment_score";
    private static final String SEVERITY_COLUMN = "severity";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String product = metaData.getDatabaseProductName().toLowerCase(Locale.ROOT);

            dropSentimentIndexIfExists(metaData, product);
            dropColumnIfExists(metaData, product, SENTIMENT_COLUMN);
            dropColumnIfExists(metaData, product, SENTIMENT_SCORE_COLUMN);
            dropColumnIfExists(metaData, product, SEVERITY_COLUMN);
        } catch (Exception e) {
            log.warn("Failed to clean feedback_analyzed sentiment columns", e);
        }
    }

    private void dropSentimentIndexIfExists(DatabaseMetaData metaData, String product) throws SQLException {
        if (!indexExists(metaData, SENTIMENT_INDEX)) {
            return;
        }
        if (isMySql(product)) {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " DROP INDEX " + SENTIMENT_INDEX);
        } else {
            jdbcTemplate.execute("DROP INDEX IF EXISTS " + SENTIMENT_INDEX);
        }
        log.info("Dropped old index {}.{}", TABLE_NAME, SENTIMENT_INDEX);
    }

    private void dropColumnIfExists(DatabaseMetaData metaData, String product, String columnName) throws SQLException {
        if (!columnExists(metaData, columnName)) {
            return;
        }
        if (isMySql(product)) {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " DROP COLUMN " + columnName);
        } else {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " DROP COLUMN IF EXISTS " + columnName);
        }
        log.info("Dropped old column {}.{}", TABLE_NAME, columnName);
    }

    private boolean indexExists(DatabaseMetaData metaData, String indexName) throws SQLException {
        try (ResultSet resultSet = metaData.getIndexInfo(null, null, TABLE_NAME, false, false)) {
            while (resultSet.next()) {
                if (indexName.equalsIgnoreCase(resultSet.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean columnExists(DatabaseMetaData metaData, String columnName) throws SQLException {
        try (ResultSet resultSet = metaData.getColumns(null, null, TABLE_NAME, columnName)) {
            if (resultSet.next()) {
                return true;
            }
        }
        try (ResultSet resultSet = metaData.getColumns(null, null, TABLE_NAME, columnName.toUpperCase(Locale.ROOT))) {
            return resultSet.next();
        }
    }

    private boolean isMySql(String product) {
        return product.contains("mysql") || product.contains("mariadb");
    }
}
