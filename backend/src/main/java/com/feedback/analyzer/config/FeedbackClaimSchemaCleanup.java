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
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackClaimSchemaCleanup implements ApplicationRunner {

    private static final String TABLE_NAME = "feedback_claim";
    private static final String STATUS_COLUMN = "status";
    private static final String CONTENT_COLUMN = "content";
    private static final String SEVERITY_COLUMN = "severity";
    private static final String PRAISE_TARGET_COLUMN = "praise_target";
    private static final String PRAISE_TARGET_VECTOR_COLUMN = "praise_target_embedding_vector";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String product = metaData.getDatabaseProductName().toLowerCase(Locale.ROOT);
            normalizeStatusColumn(metaData, product);
            addContentColumnIfMissing(metaData, product);
            addPraiseTargetColumnIfMissing(metaData);
            addPraiseTargetVectorColumnIfMissing(metaData, product);
            dropSeverityColumnIfPresent(metaData);
        } catch (Exception e) {
            log.warn("Failed to normalize feedback_claim status column", e);
        }
    }

    private void dropSeverityColumnIfPresent(DatabaseMetaData metaData) throws Exception {
        try (ResultSet columns = metaData.getColumns(null, null, TABLE_NAME, SEVERITY_COLUMN)) {
            if (!columns.next()) {
                return;
            }
        }
        jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " DROP COLUMN " + SEVERITY_COLUMN);
        log.info("Dropped legacy {}.{} column", TABLE_NAME, SEVERITY_COLUMN);
    }

    private void addContentColumnIfMissing(DatabaseMetaData metaData, String product) throws Exception {
        try (ResultSet columns = metaData.getColumns(null, null, TABLE_NAME, CONTENT_COLUMN)) {
            if (columns.next()) {
                return;
            }
        }
        if (isMySql(product)) {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + CONTENT_COLUMN + " TEXT");
        } else {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + CONTENT_COLUMN + " TEXT");
        }
        log.info("Added {}.{} column", TABLE_NAME, CONTENT_COLUMN);
    }

    private void addPraiseTargetColumnIfMissing(DatabaseMetaData metaData) throws Exception {
        try (ResultSet columns = metaData.getColumns(null, null, TABLE_NAME, PRAISE_TARGET_COLUMN)) {
            if (columns.next()) {
                return;
            }
        }
        jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + PRAISE_TARGET_COLUMN + " VARCHAR(256)");
        log.info("Added {}.{} column", TABLE_NAME, PRAISE_TARGET_COLUMN);
    }

    private void addPraiseTargetVectorColumnIfMissing(DatabaseMetaData metaData, String product) throws Exception {
        try (ResultSet columns = metaData.getColumns(null, null, TABLE_NAME, PRAISE_TARGET_VECTOR_COLUMN)) {
            if (columns.next()) {
                return;
            }
        }
        if (isMySql(product)) {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + PRAISE_TARGET_VECTOR_COLUMN + " MEDIUMTEXT");
        } else {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + PRAISE_TARGET_VECTOR_COLUMN + " TEXT");
        }
        log.info("Added {}.{} column", TABLE_NAME, PRAISE_TARGET_VECTOR_COLUMN);
    }

    private void normalizeStatusColumn(DatabaseMetaData metaData, String product) throws Exception {
        try (ResultSet columns = metaData.getColumns(null, null, TABLE_NAME, STATUS_COLUMN)) {
            if (!columns.next()) {
                return;
            }
            String typeName = columns.getString("TYPE_NAME");
            int size = columns.getInt("COLUMN_SIZE");
            boolean needNormalize = typeName != null && typeName.toLowerCase(Locale.ROOT).contains("enum");
            needNormalize = needNormalize || size < 20;
            if (!needNormalize) {
                return;
            }
        }

        if (isMySql(product)) {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " MODIFY COLUMN " + STATUS_COLUMN + " VARCHAR(20) NOT NULL");
        } else {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ALTER COLUMN " + STATUS_COLUMN + " TYPE VARCHAR(20)");
        }
        log.info("Normalized {}.{} to VARCHAR(20)", TABLE_NAME, STATUS_COLUMN);
    }

    private boolean isMySql(String product) {
        return product.contains("mysql") || product.contains("mariadb");
    }
}
