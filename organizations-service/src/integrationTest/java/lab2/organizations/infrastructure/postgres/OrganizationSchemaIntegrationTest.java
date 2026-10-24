package lab2.organizations.infrastructure.postgres;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lab2.organizations.generated.model.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class OrganizationSchemaIntegrationTest {
    private HikariDataSource source;
    private HikariDataSource isolated;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach
    void createIsolatedSchema() {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || url.isBlank())
            throw new IllegalStateException(
                    "Set TEST_DATABASE_URL to run PostgreSQL integration tests");
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("organizations");
        config.setPassword("organizations-local");
        source = new HikariDataSource(config);
        schema = "schema_test_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
        var isolatedConfig = new HikariConfig();
        isolatedConfig.setJdbcUrl(url);
        isolatedConfig.setUsername("organizations");
        isolatedConfig.setPassword("organizations-local");
        isolatedConfig.setSchema(schema);
        isolated = new HikariDataSource(isolatedConfig);
        jdbc = new JdbcTemplate(isolated);
        Flyway.configure()
                .dataSource(isolated)
                .schemas(schema)
                .defaultSchema(schema)
                .target("1")
                .load()
                .migrate();
    }

    @AfterEach
    void removeIsolatedSchema() {
        if (isolated != null) isolated.close();
        if (source != null) {
            try {
                if (schema != null)
                    new JdbcTemplate(source).execute("DROP SCHEMA " + schema + " CASCADE");
            } finally {
                source.close();
            }
        }
    }

    @Test
    void databaseRejectsInvalidScalarsAndPartialValueObjects() {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO organizations(name,coordinates_x,coordinates_y,creation_date,annual_turnover,type,address_zip_code,town_x,town_y,town_name)"
                                + " VALUES ('valid',7,417,DATE '2026-10-24',12.5,'TRUST','',-12,7,'') RETURNING id",
                        Long.class);
        var invalid =
                Map.ofEntries(
                        Map.entry("name=NULL", "23502"),
                        Map.entry("name=''", "23514"),
                        Map.entry("coordinates_x=NULL", "23502"),
                        Map.entry("coordinates_y=NULL", "23502"),
                        Map.entry("coordinates_y=2147483648", "22003"),
                        Map.entry("creation_date=NULL", "23502"),
                        Map.entry("creation_date='10000-01-01'", "23514"),
                        Map.entry("annual_turnover=0", "23514"),
                        Map.entry("annual_turnover=-1", "23514"),
                        Map.entry("annual_turnover='Infinity'", "23514"),
                        Map.entry("annual_turnover='NaN'", "23514"),
                        Map.entry("type='UNKNOWN'", "22P02"),
                        Map.entry("address_zip_code='1234567890123456789'", "23514"),
                        Map.entry("address_zip_code=NULL", "23514"),
                        Map.entry("town_x=NULL", "23514"),
                        Map.entry("town_y=NULL", "23514"),
                        Map.entry("town_name=NULL", "23514"));
        invalid.forEach(
                (assignment, state) -> {
                    var error =
                            assertThrows(
                                    DataAccessException.class,
                                    () ->
                                            jdbc.update(
                                                    "UPDATE organizations SET "
                                                            + assignment
                                                            + " WHERE id=?",
                                                    id),
                                    assignment);
                    assertEquals(
                            state,
                            ((java.sql.SQLException) error.getMostSpecificCause()).getSQLState(),
                            assignment);
                });
        assertEquals(
                1,
                jdbc.update(
                        "UPDATE organizations SET town_x=NULL,town_y=NULL,town_name=NULL WHERE id=?",
                        id));
        assertEquals(
                1,
                jdbc.update(
                        "UPDATE organizations SET address_zip_code=NULL,type=NULL,annual_turnover=NULL WHERE id=?",
                        id));
    }

    @Test
    void baselineSupportsFullWidthNumbersAndNullableValues() {
        var repository = new JdbcOrganizationRepository(isolated);
        var input =
                new OrganizationWrite("limits", new Coordinates(Long.MIN_VALUE, Integer.MAX_VALUE))
                        .annualTurnover(Double.MAX_VALUE)
                        .officialAddress(
                                new Address("")
                                        .town(new Location(Long.MIN_VALUE, Long.MAX_VALUE, "")));
        var created = repository.insert(input, LocalDate.of(2026, 10, 24));
        assertEquals(Long.MIN_VALUE, created.getCoordinates().getX());
        assertEquals(Integer.MAX_VALUE, created.getCoordinates().getY());
        assertEquals(Double.MAX_VALUE, created.getAnnualTurnover());
        assertEquals(Long.MIN_VALUE, created.getOfficialAddress().getTown().getX());
        assertEquals(Long.MAX_VALUE, created.getOfficialAddress().getTown().getY());
        assertEquals("", created.getOfficialAddress().getTown().getName());
        assertNull(created.getType());
        input.annualTurnover(Double.MIN_VALUE).officialAddress(null);
        var replaced = repository.replace(created.getId(), input, created.getCreationDate());
        assertEquals(Double.MIN_VALUE, replaced.getAnnualTurnover());
        assertNull(replaced.getOfficialAddress());
        assertEquals(created.getId(), replaced.getId());
        assertEquals(created.getCreationDate(), replaced.getCreationDate());
    }
}
