package lab2.manager.infrastructure.postgres;

import static lab2.manager.application.error.ServiceException.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.*;
import java.util.List;
import lab2.manager.application.acquisition.AcquireOrganizationService;
import lab2.manager.application.error.ServiceException;
import lab2.manager.support.InMemoryOrganizationsGateway;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

class AcquisitionJournalIntegrationTest {
    private HikariDataSource source;

    @BeforeEach
    void connect() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || url.isEmpty())
            throw new IllegalStateException(
                    "Set TEST_DATABASE_URL to run PostgreSQL integration tests");
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("orgmanager");
        config.setPassword("orgmanager-local");
        source = new HikariDataSource(config);
        Flyway.configure().dataSource(source).load().migrate();
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            s.execute("TRUNCATE acquisition_journal");
        }
    }

    @AfterEach
    void close() {
        if (source != null) source.close();
    }

    @Test
    void snapshotSurvivesRecreationAndCompletionRemovesIt() {
        try (var lease = new PostgresAcquisitionJournal(source).lock(1, 2)) {
            assertNull(lease.load());
            lease.save(List.of(10L, Long.MAX_VALUE));
        }
        try (var lease = new PostgresAcquisitionJournal(source).lock(1, 2)) {
            assertEquals(List.of(10L, Long.MAX_VALUE), lease.load().employeeIds());
            lease.complete();
        }
        try (var lease = new PostgresAcquisitionJournal(source).lock(1, 2)) {
            assertNull(lease.load());
        }
    }

    @Test
    void sameSourceCannotBeAcquiredConcurrentlyOrByAnotherAcquirer() {
        try (var first = new PostgresAcquisitionJournal(source).lock(1, 2)) {
            assertEquals(
                    CONFLICT,
                    assertThrows(
                                    ServiceException.class,
                                    () -> new PostgresAcquisitionJournal(source).lock(1, 2))
                            .kind);
            first.save(List.of(10L));
        }
        try (var second = new PostgresAcquisitionJournal(source).lock(3, 2)) {
            assertEquals(CONFLICT, assertThrows(ServiceException.class, second::load).kind);
        }
    }

    @Test
    void restartedWorkflowUsesPersistedSnapshot() {
        var gateway = new InMemoryOrganizationsGateway();
        gateway.employees.put(
                10L,
                new lab2.manager.generated.upstream.model.Employee(10L, "a").organizationId(2L));
        gateway.employees.put(
                11L,
                new lab2.manager.generated.upstream.model.Employee(11L, "b").organizationId(2L));
        gateway.failUpdate = 2;
        assertEquals(
                UPSTREAM_TIMEOUT,
                assertThrows(
                                ServiceException.class,
                                () ->
                                        new AcquireOrganizationService(
                                                        gateway,
                                                        new PostgresAcquisitionJournal(source))
                                                .acquire(1, 2))
                        .kind);
        assertEquals(
                2L,
                new AcquireOrganizationService(gateway, new PostgresAcquisitionJournal(source))
                        .acquire(1, 2)
                        .employeeCount());
        assertEquals(3, gateway.updates);
    }
}
