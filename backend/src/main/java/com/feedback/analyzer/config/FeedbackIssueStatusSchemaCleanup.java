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
public class FeedbackIssueStatusSchemaCleanup implements ApplicationRunner {

    private static final String TABLE_NAME = "feedback_issue";
    private static final String STATUS_COLUMN = "status";
    private static final String CONFIRMED_COLUMN = "confirmed";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            if (!hasTable(metaData, TABLE_NAME)) {
                return;
            }
            String product = metaData.getDatabaseProductName().toLowerCase(Locale.ROOT);
            String tableName = resolveTableName(metaData, TABLE_NAME);
            ensureConfirmedColumn(metaData, product, tableName);
            normalizeStatusColumn(metaData, product, tableName);
            migrateLegacyStatuses();
        } catch (Exception e) {
            log.warn("Failed to normalize feedback_issue status column", e);
        }
    }

    private void normalizeStatusColumn(DatabaseMetaData metaData, String product, String tableName) throws Exception {
        try (ResultSet columns = metaData.getColumns(null, null, tableName, STATUS_COLUMN)) {
            if (!columns.next()) {
                return;
            }
            String typeName = columns.getString("TYPE_NAME");
            int size = columns.getInt("COLUMN_SIZE");
            boolean needNormalize = typeName != null && typeName.toLowerCase(Locale.ROOT).contains("enum");
            needNormalize = needNormalize || size < 24;
            if (!needNormalize) {
                return;
            }
        }

        if (isMySql(product)) {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " MODIFY COLUMN " + STATUS_COLUMN + " VARCHAR(24) NOT NULL");
        } else {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ALTER COLUMN " + STATUS_COLUMN + " TYPE VARCHAR(24)");
        }
        log.info("Normalized {}.{} to VARCHAR(24)", TABLE_NAME, STATUS_COLUMN);
    }

    private void ensureConfirmedColumn(DatabaseMetaData metaData, String product, String tableName) throws Exception {
        if (hasColumn(metaData, tableName, CONFIRMED_COLUMN)) {
            return;
        }
        if (isMySql(product)) {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + CONFIRMED_COLUMN + " TINYINT(1) DEFAULT 0");
        } else {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + CONFIRMED_COLUMN + " BOOLEAN DEFAULT FALSE");
        }
        log.info("Added {}.{} column", TABLE_NAME, CONFIRMED_COLUMN);
    }

    private void migrateLegacyStatuses() {
        int merged = jdbcTemplate.update("""
                UPDATE feedback_issue
                SET status = 'MERGED'
                WHERE status = 'CLOSED'
                  AND related_issue IS NOT NULL
                AND related_issue LIKE 'ISSUE-%'
                """);
        int suggestions = jdbcTemplate.update("""
                UPDATE feedback_issue
                SET status = 'EVALUATING'
                WHERE category = 'SUGGESTION'
                  AND status IN ('OPEN', 'ACKNOWLEDGED', 'FIXING', 'RESOLVED', 'CLOSED')
                """);
        int confirmedBugs = jdbcTemplate.update("""
                UPDATE feedback_issue
                SET confirmed = 1,
                    status = 'OPEN'
                WHERE category = 'BUG'
                  AND status = 'ACKNOWLEDGED'
                """);
        int nullConfirmed = jdbcTemplate.update("""
                UPDATE feedback_issue
                SET confirmed = 0
                WHERE confirmed IS NULL
                """);
        if (merged + suggestions + confirmedBugs + nullConfirmed > 0) {
            log.info("Migrated feedback_issue statuses, merged={}, suggestions={}, confirmedBugs={}, nullConfirmed={}",
                    merged, suggestions, confirmedBugs, nullConfirmed);
        }
    }

    private boolean hasTable(DatabaseMetaData metaData, String table) throws Exception {
        try (ResultSet tables = metaData.getTables(null, null, table, null)) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = metaData.getTables(null, null, table.toUpperCase(Locale.ROOT), null)) {
            return tables.next();
        }
    }

    private String resolveTableName(DatabaseMetaData metaData, String table) throws Exception {
        try (ResultSet tables = metaData.getTables(null, null, table, null)) {
            if (tables.next()) {
                return tables.getString("TABLE_NAME");
            }
        }
        String upper = table.toUpperCase(Locale.ROOT);
        try (ResultSet tables = metaData.getTables(null, null, upper, null)) {
            if (tables.next()) {
                return tables.getString("TABLE_NAME");
            }
        }
        return table;
    }

    private boolean hasColumn(DatabaseMetaData metaData, String table, String column) throws Exception {
        try (ResultSet columns = metaData.getColumns(null, null, table, column)) {
            if (columns.next()) {
                return true;
            }
        }
        try (ResultSet columns = metaData.getColumns(null, null, table, column.toUpperCase(Locale.ROOT))) {
            return columns.next();
        }
    }

    private boolean isMySql(String product) {
        return product.contains("mysql") || product.contains("mariadb");
    }
}
