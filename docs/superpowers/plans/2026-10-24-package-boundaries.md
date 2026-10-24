# Package Boundaries Implementation Plan

> Execute inline using superpowers:executing-plans. The user selected and authorized option 1 in the conversation.

**Goal:** Refactor both services into the approved package structure while preserving their API, data and deployment behavior.

**Spec:** ../specs/2026-10-24-package-boundaries-design.md and the root OpenAPI specifications.

## Constraints

- Independent Java 17 Gradle Kotlin WAR builds, generated public DTOs, native Payara/WildFly HTTPS on 26125/26126.
- Application classes depend only on Java, generated models, and their own application packages. Frameworks and transport error models stay in adapters.
- Preserve transaction advisory locking, employee assignment/deletion atomicity, snapshot-before-transfer, persisted explicit recovery, and no mutation retries.
- Preserve the canonical YAML files, database migrations, AGENTS.md and current runtime/dependency versions.
- No Git repository exists; edit in place with a local pre-refactor backup and do not initialize Git.

## Tasks

- [x] Establish a fresh baseline using a separate PostgreSQL deployment; add failing architecture tests for the package rules.
- [x] Normalize generation namespaces; split organizations application services, repository/transaction interfaces, JDBC implementations, typed collection execution and inbound parsing; move XML, errors, controllers and configuration into their owning packages.
- [x] Split manager hiring/acquisition services and interfaces; move the journal and upstream client into infrastructure; separate XML directions, response mapping, HTTP errors and CDI configuration.
- [x] Mirror tests in the source packages, extract test helpers, and introduce explicit integrationTest source sets/tasks. Update Docker test orchestration and documentation.
- [x] Run independent clean builds, unit/architecture tests and PostgreSQL integration tests. Rebuild/deploy both WARs, run API/TLS acceptance checks and prove acquisition recovery across server restarts.
- [x] Obtain an independent read-only review; fix material findings with regression evidence and record results.

## Review focus

- Transaction checks and writes must share the exact same JDBC transaction after extracting repositories.
- Generated class names and XML roots must survive namespace changes and clean generation.
- HTTP statuses/details must remain identical after removing HTTP concerns from application failures.
- CDI/Spring discovery must find moved configuration and resources without injecting unproxyable final services.
- Separate integration tests must actually run, reuse named fixtures and report zero skipped tests when configured.
