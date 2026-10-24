# Typed organization persistence

Migration policy update: the user's later instruction supersedes the incremental-upgrade approach below. Keep one initial V1 migration per service, creating the complete current schema directly; recreate the local database after changes. No legacy-upgrade implementation is retained.

The user requested replacing the organization's JSONB document with model fields and explicitly chose to preserve the current OpenAPI types and validation. Lab 1's different numeric bounds and required fields are not adopted in this change.

Keep one organization row with typed columns: identity `id`, `name`, `coordinates_x` BIGINT, `coordinates_y` INTEGER, `creation_date` DATE, optional `annual_turnover` DOUBLE PRECISION, optional enum `type`, optional `address_zip_code`, and optional `town_x`/`town_y` BIGINT and `town_name`. Coordinates, address and town are owned value objects without independent IDs. Embedding their columns avoids extra identities, joins and orphan cleanup. Separate child tables are unnecessary for these exclusively owned values; retaining JSONB does not meet the request.

Use SQL NOT NULL, CHECK and enum constraints for the existing API invariants. Null address ZIP means no address; empty ZIP means a present address with an empty ZIP. Town fields must be either all present or all absent and require an address. Turnover, when present, must be finite and positive. Preserve int64 precision and nullable type/turnover.

Add Flyway V2, leaving the applied V1 migration unchanged. Backfill typed columns from each legacy JSON document in one PostgreSQL transaction, validate constraints, then remove `body`. Existing IDs, creation dates, identity sequence and employee foreign keys remain intact. Invalid legacy values must fail the migration without partial changes; never invent replacements or erase data.

Replace JSON serialization with explicit prepared SQL and an organization RowMapper in the existing PostgreSQL package. Application ports, service behavior, XML APIs, manager workflow and package boundaries remain unchanged. The manager's separate acquisition journal is outside this organization-storage change.

Verify real V1-to-V2 upgrades, optional nested values, constraints, rollback on invalid data, CRUD/concurrency, both test suites, HTTPS acceptance and restart recovery in an isolated Docker project. Rebuild the existing deployment after verification and confirm its records survive.
