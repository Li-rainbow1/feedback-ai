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

@Slf4j
@Component
@RequiredArgsConstructor
public class NonBugSeverityCleanup implements ApplicationRunner {

    private static final String[] TABLES = {"feedback_issue"};

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            for (String table : TABLES) {
                if (hasTable(metaData, table)) {
                    int updated = jdbcTemplate.update("""
                            UPDATE %s
                            SET severity = 'LOW'
                            WHERE category IS NOT NULL
                              AND UPPER(category) <> 'BUG'
                              AND (severity IS NULL OR UPPER(severity) <> 'LOW')
                            """.formatted(table));
                    if (updated > 0) {
                        log.info("Normalized {} non-BUG severity rows to LOW: {}", table, updated);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to normalize non-BUG severity", e);
        }
    }

    private boolean hasTable(DatabaseMetaData metaData, String table) throws Exception {
        try (ResultSet tables = metaData.getTables(null, null, table, null)) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = metaData.getTables(null, null, table.toUpperCase(), null)) {
            return tables.next();
        }
    }
}
