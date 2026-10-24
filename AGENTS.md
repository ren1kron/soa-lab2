# Repository Guidelines

## Project Structure & Module Organization

The root `openapi.yaml` and `openapi-manager.yaml` are the authoritative XML API contracts. `organizations-service/` contains the Spring MVC WAR deployed on Payara; `orgmanager-service/` contains the Jakarta REST WAR deployed on WildFly. Each service owns its Gradle Kotlin build and wrapper. Java sources and JUnit tests live in `src/main/java` and `src/test/java`; Flyway migrations live in `src/main/resources/db/migration`. Root `deploy/`, `scripts/`, and `tests/` contain Docker configuration, utilities, and acceptance checks.

## Build, Test, and Development Commands

- `docker compose up -d --build --wait`: build and start both HTTPS services and PostgreSQL.
- `cd organizations-service && ./gradlew clean build`: generate contracts, run tests, and package the first WAR; use the same command in `orgmanager-service/` for the manager.
- `./gradlew openApiGenerate`: regenerate service interfaces and models. The manager also has `generateUpstreamModels`.
- `sh scripts/test.sh`: run PostgreSQL and HTTPS acceptance checks in an isolated temporary Docker deployment.
- `sh scripts/export-certificates.sh`: export public certificates for `curl --cacert`.

## Coding Style & Naming Conventions

Use Java 17, four-space indentation, PascalCase types, and camelCase members. Keep controllers/resources thin and persistence separate from workflow logic. Use generated transport models; never edit files under `build/generated/`. Regenerate after specification or template changes. No formatter or linter is currently configured.

## Testing Guidelines

JUnit 5 covers XML validation, query semantics, HTTP behavior, upstream failures, and acquisition recovery. Name tests `*Test.java`, with behavior-focused method names. PostgreSQL tests require `TEST_DATABASE_URL`; the isolated test script configures it. Preserve full int64 values and test mutation failures before retrying. Acceptance tests exercise actual Payara/WildFly deployments and certificate validation.

## Commit & Pull Request Guidelines

This directory has no existing Git history or established commit convention. Use concise imperative subjects, such as `Add acquisition recovery tests`. Pull requests should describe behavior changes, contract impact, and exact verification commands/results. Include XML examples for changed API behavior and explain migration or deployment changes.

## Configuration & Security

The manager accesses domain data through HTTPS only. Keep credentials, certificates, and caches out of version control. Preserve disabled HTTP listeners and certificate/hostname verification. Never automatically retry uncertain mutations.
