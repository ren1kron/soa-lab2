package lab2.organizations.config;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SharedSchemaMigrationIntegrationTest {
    private static final String HISTORY = "organizations_schema_history";
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach
    void createSharedSchemaWithForeignTable() {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || url.isBlank())
            throw new IllegalStateException(
                    "Set TEST_DATABASE_URL to run PostgreSQL integration tests");
        schema = "shared_test_" + UUID.randomUUID().toString().replace("-", "");
        var config = new HikariConfig();
        config.setJdbcUrl(url + "?currentSchema=" + schema);
        config.setUsername("organizations");
        config.setPassword("organizations-local");
        source = new HikariDataSource(config);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE TABLE " + schema + ".other_service(id INTEGER)");
        jdbc.execute("INSERT INTO " + schema + ".other_service VALUES (7)");
    }

    @AfterEach
    void dropSharedSchema() {
        if (source == null) return;
        try {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        } finally {
            source.close();
        }
    }

    @Test
    void appliesV1BesideExistingTablesWithItsOwnHistory() {
        DatabaseConfiguration.migrate(source, HISTORY);

        assertEquals(1, count("SELECT COUNT(*) FROM other_service WHERE id=7"));
        assertEquals(0, count("SELECT COUNT(*) FROM organizations"));
        assertEquals(0, count("SELECT COUNT(*) FROM employees"));
        assertEquals(
                1, count("SELECT COUNT(*) FROM " + HISTORY + " WHERE version='1' AND success"));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM pg_tables WHERE schemaname='"
                                + schema
                                + "' AND tablename='flyway_schema_history'"));
    }

    @Test
    void restartDoesNotReapplyV1() {
        DatabaseConfiguration.migrate(source, HISTORY);
        DatabaseConfiguration.migrate(source, HISTORY);

        assertEquals(1, count("SELECT COUNT(*) FROM " + HISTORY + " WHERE version='1'"));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
