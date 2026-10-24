# SOA Laboratory Work #2

Two independent Java 17 services implementing the existing XML OpenAPI contracts:

| Service | Implementation | Local URL |
| --- | --- | --- |
| Organizations and employees | Spring MVC 6.2, Payara 6 | `https://localhost:26125` |
| Organization manager | Jakarta REST 3.1, WildFly 35 / RESTEasy | `https://localhost:26126` |

The client application and Helios deployment are deferred. The required route spelling is `/orgmanager/acquise/{acquirerId}/{acquiredId}`.

## Run with Docker

Install and start Docker Desktop or Docker Engine with Compose v2, then run from this directory:

```sh
docker compose up -d --build --wait --wait-timeout 240
sh scripts/export-certificates.sh
curl --cacert .local/certs/organizations.crt https://localhost:26125/organizations
```

The images build each WAR independently. No local Java or Gradle installation is needed for Docker deployment. PostgreSQL and administration interfaces are not published. Both application servers terminate TLS themselves; their HTTP application listeners are disabled. HTTP requests to the HTTPS ports fail.

The certificate initialization container creates separate self-signed RSA server certificates, covering localhost, `127.0.0.1`, and Docker service names. Public certificates are exported into `.local/certs/`; private keys remain in Docker volumes. The manager trusts the organizations certificate through its dedicated PKCS12 truststore and retains hostname verification. Use `--cacert` instead of disabling verification. Browsers need an explicit certificate exception or local trust configuration.

Optional `.env` settings are `ORGANIZATIONS_HTTPS_PORT`, `MANAGER_HTTPS_PORT`, `APP_TIME_ZONE`, and comma-separated `CORS_ORIGINS`. Default ports are 26125/26126, the date-generation time zone is Europe/Moscow, and cross-origin access is disabled. Existing specification URLs remain Lab #1 placeholders; use the HTTPS URLs above.

## Independent builds and generation

Each service has its own Gradle 8.14.3 wrapper and Kotlin build configuration. Run with JDK 17:

```sh
cd organizations-service
./gradlew clean build
```

Run the same commands in `orgmanager-service/`. WARs are written to each service's `build/libs/`. There is no root Gradle build or Maven POM.

Both projects use `config`, `web`, `application`, and `infrastructure` packages within a single Gradle module. Application services define workflows and depend on interfaces under `application/port`; PostgreSQL and HTTPS adapters implement those interfaces. Controllers and resources handle transport concerns. See [package organization](docs/architecture.md) for both trees and dependency rules.

OpenAPI Generator 7.19.0 runs automatically before compilation. Organizations reads `../openapi.yaml`, generating Spring interfaces and models without Boot scaffolding or runtime dependencies. The manager reads `../openapi-manager.yaml` for Jakarta interfaces/models and separately generates upstream models from the first specification. Generated Java remains under `build/generated/` and is not committed. `openApiGenerate` regenerates a service contract; the manager also exposes `generateUpstreamModels`.

The Spring query-parameter template preserves unbounded page numbers using `BigInteger`, while model fields retain their int32/int64 types. Custom XML adapters enforce lowercase roots, list/detail wrappers, strict validation, and optional-field omission. Business logic is handwritten.

## Formatting and Lombok

Run these commands in either service directory:

```sh
./gradlew spotlessApply  # Format handwritten Java and Gradle Kotlin scripts
./gradlew spotlessCheck  # Verify formatting without changing files
```

Spotless uses google-java-format in AOSP style (four-space indentation) and ktfmt for Gradle scripts. Main, unit-test and integration-test Java sources are included; generated OpenAPI files under `build/generated/` are excluded. Gradle `check`/`build`, Docker image builds, and `scripts/test.sh` enforce formatting.

Lombok is configured as a compile-only dependency and annotation processor for every source set, following its [Gradle setup](https://projectlombok.org/setup/gradle). Application services use `@RequiredArgsConstructor` for their final dependencies. Lombok is not packaged in the deployed WARs. Each service has its own `lombok.config`, so its settings remain independent of parent directories.

## Test

```sh
sh scripts/test.sh
```

This creates a uniquely named Docker deployment with temporary databases, runs both JUnit suites against PostgreSQL, executes HTTPS acceptance tests against Payara and WildFly, and removes only that test deployment and its volumes. It also seeds an interrupted acquisition fixture, restarts both servers, verifies saved-progress recovery, and repeats the acceptance suite. Docker images and Gradle dependencies are cached.

Independent `./gradlew test` runs unit and ArchUnit dependency checks without a database. PostgreSQL tests live in `src/integrationTest/java` and run explicitly with `./gradlew integrationTest`; this task fails if `TEST_DATABASE_URL` is missing. Use a disposable database because these tests truncate their service's tables. `scripts/test.sh` supplies isolated databases and runs both tasks. For an existing disposable PostgreSQL instance:

```sh
# In organizations-service/, with the local test role/password from Compose:
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/organizations ./gradlew integrationTest
# In orgmanager-service/, use the orgmanager database instead.
```

Reports are under each service's `build/reports/tests/test/` and `build/reports/tests/integrationTest/`. Acceptance scenarios cover all 14 operations, XML errors, filtering/sorting/pagination, multi-page acquisitions, TLS trust, and disabled HTTP listeners. JUnit additionally covers partial failures and persisted recovery.

## Persistence and recovery

Separate PostgreSQL databases and roles isolate domain data from the manager's acquisition journal. Flyway applies migrations at application startup. The manager never reads domain tables directly.

Organizations use typed columns for their name, coordinates, creation date, turnover, type, address ZIP, and town. Coordinates/address/town are owned values embedded in the organization row; employees reference the organization's generated ID. SQL constraints enforce required values, enum membership, ZIP length, finite positive turnover, and complete optional towns. Column types and nullability follow the current OpenAPI contracts.

Both services keep exactly one Flyway migration containing their complete current schema: `V1__domain.sql` for organizations and `V1__acquisition_journal.sql` for the manager. This is a disposable laboratory project: consolidate future schema changes into the existing initial migration instead of adding incremental versions. Recreate the local database after changing a migration, since its Flyway checksum changes.

To recreate the database and both services while keeping the server certificates (this deletes local application data):

```sh
docker compose down
docker volume rm soa-lab2_postgres-data
docker compose up -d --build --wait --wait-timeout 240
```

Acquisition commits the complete employee ID snapshot before transferring anyone. Repeat the same POST explicitly to resume an unfinished operation, including after restarting the manager. It reads current ownership, skips transferred employees, and reports removed or unexpectedly reassigned employees as `409`. A failed workflow retains completed transfers and its journal. Successful completion removes the journal; a later fresh request for the deleted source returns `404`. There is no distributed transaction or automatic mutation retry.

For the lab's collection size, filtering and sorting operate on one database snapshot loaded into memory. Domain mutations use a PostgreSQL transaction advisory lock to serialize writes, plus an employer foreign key. This favors predictable correctness over high write throughput.

```sh
docker compose logs --tail 100 organizations-service orgmanager-service
docker compose down
```

Ordinary shutdown preserves database and certificate volumes. To deliberately erase this deployment's data and certificates, use `docker compose down --volumes`. Credentials in Compose and database initialization are local development defaults.

See [API examples](docs/api-examples.md) for every operation and [repository guidelines](AGENTS.md) for contribution instructions.
