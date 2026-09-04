package com.devpilot.server;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

final class TestDatabaseReset {

    private TestDatabaseReset() {
    }

    static void reset(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            List<String> tables = jdbcTemplate.queryForList("""
                    SELECT TABLE_NAME
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = 'public'
                      AND TABLE_TYPE = 'BASE TABLE'
                      AND TABLE_NAME NOT IN ('flyway_schema_history', 'sys_role')
                    """, String.class);
            for (String table : tables) {
                jdbcTemplate.execute("TRUNCATE TABLE \"" + table.replace("\"", "\"\"") + "\"");
            }
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }
}
