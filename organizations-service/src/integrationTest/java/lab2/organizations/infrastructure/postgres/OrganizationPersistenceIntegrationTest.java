package lab2.organizations.infrastructure.postgres;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.*;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import lab2.organizations.application.employee.EmployeeService;
import lab2.organizations.application.error.ServiceException;
import lab2.organizations.application.organization.OrganizationService;
import lab2.organizations.generated.model.*;
import lab2.organizations.web.validation.CollectionQueryParser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

class OrganizationPersistenceIntegrationTest {
    private HikariDataSource source;
    private OrganizationService organizations;
    private EmployeeService employees;

    @BeforeEach
    void connect() {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || url.isEmpty())
            throw new IllegalStateException(
                    "Set TEST_DATABASE_URL to run PostgreSQL integration tests");
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("organizations");
        config.setPassword("organizations-local");
        source = new HikariDataSource(config);
        Flyway.configure().dataSource(source).load().migrate();
        var organizationRepository = new JdbcOrganizationRepository(source);
        var employeeRepository = new JdbcEmployeeRepository(source);
        var writes = new PostgresWriteTransaction(source);
        organizations =
                new OrganizationService(
                        organizationRepository,
                        employeeRepository,
                        writes,
                        Clock.fixed(
                                Instant.parse("2026-10-24T00:00:00Z"), ZoneId.of("Europe/Moscow")));
        employees = new EmployeeService(employeeRepository, organizationRepository, writes);
        new org.springframework.jdbc.core.JdbcTemplate(source)
                .execute("TRUNCATE employees, organizations RESTART IDENTITY CASCADE");
    }

    @AfterEach
    void close() {
        if (source != null) source.close();
    }

    private OrganizationWrite org(String name) {
        return new OrganizationWrite(name, new Coordinates(Long.MIN_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void replacementPreservesIdDateAndEmployees() {
        var o = organizations.create(org("before").annualTurnover(2.5));
        var e = employees.create(new EmployeeWrite("worker").organizationId(o.getId()));
        var updated = organizations.replace(o.getId(), org("after"));
        assertEquals(o.getId(), updated.getId());
        assertEquals(o.getCreationDate(), updated.getCreationDate());
        assertNull(updated.getAnnualTurnover());
        assertEquals(o.getId(), employees.get(e.getId()).getOrganizationId());
        assertEquals(
                ServiceException.Kind.CONFLICT,
                assertThrows(ServiceException.class, () -> organizations.delete(o.getId())).kind);
    }

    @Test
    void unknownEmployerDoesNotModifyEmployee() {
        var e = employees.create(new EmployeeWrite("original"));
        assertEquals(
                ServiceException.Kind.NOT_FOUND,
                assertThrows(
                                ServiceException.class,
                                () ->
                                        employees.replace(
                                                e.getId(),
                                                new EmployeeWrite("bad").organizationId(999L)))
                        .kind);
        assertEquals("original", employees.get(e.getId()).getName());
    }

    @Test
    void omissionUnassignsAndAllowsDeletion() {
        var o = organizations.create(org("org"));
        var e = employees.create(new EmployeeWrite("worker").organizationId(o.getId()));
        employees.replace(e.getId(), new EmployeeWrite("worker"));
        assertNull(employees.get(e.getId()).getOrganizationId());
        organizations.delete(o.getId());
        assertEquals(
                ServiceException.Kind.NOT_FOUND,
                assertThrows(ServiceException.class, () -> organizations.get(o.getId())).kind);
    }

    @Test
    void deleteByTypeRejectsTheFirstMatchingOrganizationWhenItHasEmployees() {
        var first = organizations.create(org("first").type(OrganizationType.PUBLIC));
        var second = organizations.create(org("second").type(OrganizationType.PUBLIC));
        employees.create(new EmployeeWrite("worker").organizationId(first.getId()));
        assertEquals(
                ServiceException.Kind.CONFLICT,
                assertThrows(
                                ServiceException.class,
                                () -> organizations.deleteByType(OrganizationType.PUBLIC))
                        .kind);
        assertEquals(second.getId(), organizations.get(second.getId()).getId());
        assertEquals(first.getId(), organizations.get(first.getId()).getId());
    }

    @Test
    void typedQueryPreservesNestedInt64FilteringAndNullLastSorting() {
        var populated =
                organizations.create(
                        org("populated")
                                .officialAddress(
                                        new Address("1234567")
                                                .town(new Location(Long.MAX_VALUE, 1L, "Town"))));
        organizations.create(org("absent"));
        var query =
                CollectionQueryParser.parse(
                        Map.of(
                                "filter",
                                new String[] {"officialAddress.town.x:eq:" + Long.MAX_VALUE}),
                        false);
        assertEquals(
                List.of(populated.getId()),
                organizations.list(query).items().stream().map(Organization::getId).toList());
        var sort =
                CollectionQueryParser.parse(
                        Map.of("sort", new String[] {"-officialAddress.town.x"}), false);
        assertEquals("populated", organizations.list(sort).items().get(0).getName());
    }

    @Test
    void repositoryReadsAndWritesActualColumnsIncludingOptionalNestedValues() {
        var input =
                org("before")
                        .annualTurnover(Double.MAX_VALUE)
                        .type(OrganizationType.TRUST)
                        .officialAddress(
                                new Address("")
                                        .town(new Location(Long.MIN_VALUE, Long.MAX_VALUE, "")));
        var created = organizations.create(input);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        var stored =
                jdbc.queryForMap(
                        "SELECT name,coordinates_x,coordinates_y,annual_turnover,type,address_zip_code,town_x,town_y,town_name FROM organizations WHERE id=?",
                        created.getId());
        assertEquals("before", stored.get("name"));
        assertEquals(Long.MIN_VALUE, stored.get("coordinates_x"));
        assertEquals(Integer.MAX_VALUE, stored.get("coordinates_y"));
        assertEquals(Double.MAX_VALUE, stored.get("annual_turnover"));
        assertEquals("TRUST", stored.get("type").toString());
        assertEquals("", stored.get("address_zip_code"));
        assertEquals(Long.MIN_VALUE, stored.get("town_x"));
        assertEquals(Long.MAX_VALUE, stored.get("town_y"));
        assertEquals("", stored.get("town_name"));
        jdbc.update("UPDATE organizations SET name='after',town_x=123 WHERE id=?", created.getId());
        var reread = organizations.get(created.getId());
        assertEquals("after", reread.getName());
        assertEquals(123L, reread.getOfficialAddress().getTown().getX());
        assertEquals(Double.MAX_VALUE, reread.getAnnualTurnover());
        organizations.replace(created.getId(), org("replacement"));
        var replaced =
                jdbc.queryForMap(
                        "SELECT annual_turnover,type,address_zip_code,town_x,town_y,town_name FROM organizations WHERE id=?",
                        created.getId());
        assertTrue(replaced.values().stream().allMatch(java.util.Objects::isNull));
        assertEquals(
                created.getCreationDate(), organizations.get(created.getId()).getCreationDate());
    }

    @Test
    void concurrentAssignmentAndDeletionCannotCreateDanglingEmployer() throws Exception {
        var o = organizations.create(org("org"));
        var e = employees.create(new EmployeeWrite("worker"));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var assignment =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    employees.replace(
                                            e.getId(),
                                            new EmployeeWrite("worker").organizationId(o.getId()));
                                    return "OK";
                                } catch (ServiceException x) {
                                    return x.kind.name();
                                }
                            });
            var deletion =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    organizations.delete(o.getId());
                                    return "OK";
                                } catch (ServiceException x) {
                                    return x.kind.name();
                                }
                            });
            start.countDown();
            String a = assignment.get(10, TimeUnit.SECONDS), d = deletion.get(10, TimeUnit.SECONDS);
            assertTrue(
                    (a.equals("OK") && d.equals("CONFLICT"))
                            || (a.equals("NOT_FOUND") && d.equals("OK")));
        } finally {
            executor.shutdownNow();
        }
    }
}
