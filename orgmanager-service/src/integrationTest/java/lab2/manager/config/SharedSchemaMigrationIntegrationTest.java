package lab2.manager.config;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.*;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.*;

class SharedSchemaMigrationIntegrationTest {
    private static final String HISTORY = "manager_schema_history";
    private HikariDataSource source;
    private String schema;

    @BeforeEach
    void createSharedSchemaWithForeignTable() throws SQLException {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || url.isEmpty())
            throw new IllegalStateException(
                    "Set TEST_DATABASE_URL to run PostgreSQL integration tests");
        schema = "shared_test_" + UUID.randomUUID().toString().replace("-", "");
        var config = new HikariConfig();
        config.setJdbcUrl(url + "?currentSchema=" + schema);
        config.setUsername("orgmanager");
        config.setPassword("orgmanager-local");
        source = new HikariDataSource(config);
        execute("CREATE SCHEMA " + schema);
        execute("CREATE TABLE " + schema + ".other_service(id INTEGER)");
        execute("INSERT INTO " + schema + ".other_service VALUES (7)");
    }

    @AfterEach
    void dropSharedSchema() throws SQLException {
        if (source == null) return;
        try {
            execute("DROP SCHEMA " + schema + " CASCADE");
        } finally {
            source.close();
        }
    }

    @Test
    void appliesV1BesideExistingTablesWithItsOwnHistory() throws SQLException {
        DatabaseConfiguration.migrate(source, HISTORY);

        assertEquals(1, count("SELECT COUNT(*) FROM other_service WHERE id=7"));
        assertEquals(0, count("SELECT COUNT(*) FROM acquisition_journal"));
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
    void restartDoesNotReapplyV1() throws SQLException {
        DatabaseConfiguration.migrate(source, HISTORY);
        DatabaseConfiguration.migrate(source, HISTORY);

        assertEquals(1, count("SELECT COUNT(*) FROM " + HISTORY + " WHERE version='1'"));
    }

    private void execute(String sql) throws SQLException {
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private int count(String sql) throws SQLException {
        try (var c = source.getConnection();
                var s = c.createStatement();
                var rows = s.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
