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
public class FeedbackIssueTriageSchemaCleanup implements ApplicationRunner {

    private static final String TABLE_NAME = "feedback_issue";

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
            addColumnIfMissing(metaData, product, "priority", "VARCHAR(2)");
            addColumnIfMissing(metaData, product, "triage_source", "VARCHAR(32)");
            addColumnIfMissing(metaData, product, "triage_reason", "TEXT");
            backfillDefaults();
        } catch (Exception e) {
            log.warn("Failed to normalize feedback_issue triage columns", e);
        }
    }

    private void addColumnIfMissing(DatabaseMetaData metaData, String product, String column, String type) throws Exception {
        try (ResultSet columns = metaData.getColumns(null, null, TABLE_NAME, column)) {
            if (columns.next()) {
                return;
            }
        }
        jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + column + " " + type);
        log.info("Added {}.{} column for {}", TABLE_NAME, column, product);
    }

    private void backfillDefaults() {
        int priorities = jdbcTemplate.update("""
                UPDATE feedback_issue
                SET priority = 'P3'
                WHERE priority IS NULL OR priority = ''
                """);
        int sources = jdbcTemplate.update("""
                UPDATE feedback_issue
                SET triage_source = 'SYSTEM_DEFAULT'
                WHERE triage_source IS NULL OR triage_source = ''
                """);
        if (priorities + sources > 0) {
            log.info("Backfilled feedback_issue triage defaults, priorities={}, sources={}", priorities, sources);
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
}
