# Typed Organization Persistence Implementation Plan

> Execute inline with superpowers:executing-plans. The user authorized the change and explicitly selected the existing OpenAPI contract.

**Goal:** Replace organization JSONB storage with typed PostgreSQL fields while retaining data and API behavior.

**Architecture:** Embedded value-object columns, SQL constraints, an explicit JDBC RowMapper, and a transactional Flyway V2 upgrade.

**Tech stack:** Existing Java 17, Spring JDBC, PostgreSQL 17 and Flyway; no new dependencies.

**Spec:** ../specs/2026-10-24-relational-organizations-design.md

## Constraints

- Preserve OpenAPI files, generated model types, independent WAR builds and AGENTS.md.
- Preserve applied V1 checksum, IDs, dates, identity generation and employee foreign keys.
- Use a separate Docker deployment and a local backup; no Git repository exists.

## Tasks

- [x] Add a real V1-upgrade test with full and absent nested values; run it against the existing implementation and observe missing typed columns.
- [x] Add V2 typed columns/backfill/checks/enum and replace OrganizationDocumentMapper with OrganizationRowMapper; update repository wiring and tests.
- [x] Test SQL rejection of invalid fields and partial nested values, transactional rollback for invalid legacy data, and repository round trips through actual columns. Run both Gradle test suites and PostgreSQL tests.
- [x] Update architecture/README documentation, rebuild both services, and verify HTTPS APIs and acquisition restart recovery in isolated Docker.
- [x] Obtain a fresh read-only review, address material findings, then update the existing local deployment and verify preserved data.

## Review focus

- Empty ZIP and town names must remain distinct from absent objects.
- Failed upgrade must preserve legacy JSON and employee links atomically.
- int64 extrema and near-boundary positive doubles must survive typed round trips.
- Type-based deletion must retain its smallest-ID semantics and shared write transaction.
- No JSONB document access or serializer should remain in organization repository code.
