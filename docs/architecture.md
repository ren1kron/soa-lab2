# Service package organization

The services remain independent Java 17 Gradle projects, each producing one WAR. Package boundaries separate transport, workflows, and external systems without adding Gradle submodules.

## Organizations service

Handwritten Java lives under `organizations-service/src/main/java/lab2/organizations/`:

```text
config/                       Spring wiring, database startup, MVC setup
web/
  controller/                 OrganizationsController, EmployeesController
  validation/                 RequestGuard, CollectionQueryParser, scalar rules
  xml/                        Request reader, response writer, message converter
  error/                      Request errors, application-to-HTTP mapping
application/
  organization/               OrganizationService: CRUD, delete/count by type
  employee/                   EmployeeService: CRUD and employer validation
  port/                       OrganizationRepository, EmployeeRepository, WriteTransaction
  query/                      Typed collection filtering/sorting, PageResult
  error/                      Protocol-independent ServiceException
infrastructure/postgres/      JDBC repositories, organization row mapper, write transaction
```

Repository checks and writes run inside the same transaction and advisory lock. Request parsing produces a validated query; application code reads typed model fields without depending on HTTP parameters or Jackson.

Organization persistence maps directly to PostgreSQL columns: `name`, `coordinates_x`, `coordinates_y`, `creation_date`, `annual_turnover`, `type`, `address_zip_code`, `town_x`, `town_y`, and `town_name`, alongside the generated `id`. A null ZIP denotes an absent address; an empty ZIP preserves a present address. All town columns are present together or absent together. The initial Flyway migration creates this schema directly. PostgreSQL constraints follow the existing OpenAPI types and validation.

## Manager service

Handwritten Java lives under `orgmanager-service/src/main/java/lab2/manager/`:

```text
config/                       CDI wiring, database/TLS configuration, REST registration
web/
  resource/                   ManagerResource
  validation/                 ManagerRequestValidator
  mapping/                    AcquisitionResponseMapper
  xml/                        ManagerResponseWriter
  error/                      Request errors, application-to-HTTP mapping
application/
  hiring/                     HireEmployeeService
  acquisition/                AcquireOrganizationService, outcome and snapshot records
  port/                       OrganizationsGateway, AcquisitionJournal
  error/                      Protocol-independent ServiceException and details
infrastructure/
  organizations/              HTTPS client, upstream XML reader/writer, error translator
  postgres/                   Acquisition journal and snapshot serializer
```

Acquisition persists its snapshot before issuing mutations, holds a session advisory lease, and removes the journal only after final response data is available. Each journal write commits independently; no database transaction spans upstream calls.

## Dependencies and generated contracts

`web` calls `application`; `infrastructure` implements application ports; `config` assembles them. Application bytecode depends only on Java, its application packages, and generated models; Lombok annotations generate constructors at compile time. Adapters do not depend on configuration or each other's implementation. ArchUnit tests enforce these rules.

OpenAPI output stays under `build/generated/`: `lab2.organizations.generated.{api,model}`, `lab2.manager.generated.{api,model}`, and `lab2.manager.generated.upstream.model`. Generation clears its own output before writing. Do not edit generated classes. The manager maps upstream organization models into its public response models in `web/mapping`.

## Tests and resources

`src/test/java` mirrors production packages and runs with `test`. Manager fixtures under `support` implement application ports; no test depends on another test class. `src/integrationTest/java` contains real PostgreSQL checks and runs with `integrationTest`, requiring `TEST_DATABASE_URL`. The integration source set can reuse named test fixtures.

Flyway migrations remain in `src/main/resources/db/migration`, deployment descriptors in `src/main/webapp/WEB-INF`, and server/TLS configuration under root `deploy/`. Root `tests/` exercises the deployed APIs over HTTPS. See the [README](../README.md#test) for commands.

For both services, always consolidate schema changes into the single existing `V1` migration. This lab has no production migration history to maintain; recreate the local database when that migration changes. Do not add incremental migration files or legacy-upgrade code. Schema integration tests exercise creation from the current initial migration, constraints, and repository behavior.
