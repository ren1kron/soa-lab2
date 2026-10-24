# Typed organization persistence progress

Superseded migration policy: the user subsequently requested one consolidated initial migration per service. V1 now creates the typed schema directly; V2 and legacy-upgrade tests are removed. Future schema changes must be merged into V1 and applied to a recreated development database. Earlier entries below describe the previous implementation history.

Plan: docs/superpowers/plans/2026-10-24-relational-organizations.md

The user explicitly selected existing OpenAPI types/validation after seeing the differences from the Lab 1 classes. One existing local organization and employee must survive; its optional turnover/type and full-width coordinates are valid under that contract.

Backup: .build-cache/model-db/before.tgz. Isolated Docker project: soa-lab2-model-db. Native inline execution, with one final read-only review; no Git metadata exists.

- Migration regression tests: four tests observed failing as expected (missing columns, absent checks, invalid data accepted).
- Migration/repository implementation: complete. The first full green run passed all organization unit and integration tests.
- Constraints, rollback, full verification and documentation: complete. Both service suites passed; numeric-limit upgrade tests preserve int64 extrema and Double.MIN_VALUE/MAX_VALUE. Failed-upgrade repair/retry and continuing identity generation are verified. README and architecture docs describe typed columns.
- Deployment/recovery/final review: complete. The material review finding was fixed with RED→GREEN coverage; final HTTPS tests and restart recovery passed. The local deployment is healthy and its API data is unchanged.

Decisions: embed owned value objects in the organization row rather than create unrelated identities and child-table lifecycle rules. Existing authorization covers implementation and local redeployment; no extra design approval is needed. Preserve every applied migration and add V2.

Pre-deployment SQL backup of the user's local organizations database: .build-cache/model-db/dev-organizations-before.sql. The OpenAPI files and original V1 migration were compared byte-for-byte with the source backup and are unchanged.

Fresh read-only review found one material edge: malformed legacy optional objects could collapse to SQL NULL. The new invalidLegacyObjectShapesAndScalarTypesCannotBeSilentlyDiscarded regression test reproduced it (RED). V2 now validates legacy object structure, required children and JSON scalar types before extracting values; unknown fields also stop the upgrade. The isolated verification schema was reset for the revised, not-yet-shipped V2; the user's local database is still on unchanged V1. Full GREEN verification is running. No other review findings were reported.

Final: fixed malformed legacy object loss — invalidLegacyObjectShapesAndScalarTypesCannotBeSilentlyDiscarded observed RED→GREEN. It checks twelve malformed-object/type/unknown-field cases with original JSON retained after failure. Whole suites now pass 63 tests (organizations 22 unit + 12 PostgreSQL, manager 26 unit + 3 PostgreSQL), zero errors/failures/skips. No deferred review findings remain.

Rebuilt final organization image after the review fix; both application servers are healthy against the revised migration in isolated Docker. The original OpenAPI files and applied V1 migration remain byte-for-byte unchanged. HTTPS acceptance/restart verification of this final image is in progress.

All five HTTPS acceptance scenarios passed on the final image, including every API operation and disabled HTTP listeners. A saved acquisition fixture has been seeded, and both servers are restarting for recovery verification.

Restart recovery passed on the final typed schema: saved IDs resumed, the completed transfer was preserved, the source was removed and the journal was completed. Final post-restart acceptance is running. The existing local deployment has not yet been migrated.

Final post-restart HTTPS acceptance: all five scenarios passed again. The user’s local organization image is being deployed; Flyway will upgrade its existing V1 schema, with a SQL backup and API snapshot already saved. The task-owned Docker verification deployment is being removed independently.

Completion: local Flyway versions 1 and 2 are successful. information_schema shows eleven typed organization columns and no body/JSONB column. The local API snapshot before/after matches all organization and employee records exactly (one each). Both services are healthy. Only the task-owned verification deployment and volumes were removed. Source and SQL backups remain under .build-cache/model-db; no Git repository exists, and no branch/commit step is applicable.

Migration consolidation follow-up: merged the complete typed schema into organizations V1, removed V2/JSON upgrade code, and replaced legacy migration tests with baseline schema tests. Both projects now have one initial migration, with the future rule documented in README and architecture.md. Verified 60 unit/architecture/PostgreSQL tests, zero failures/errors/skips. Backed up both local databases to .build-cache/consolidation-*-before.sql, recreated the PostgreSQL volume while keeping TLS volumes, and restarted both healthy services on the new baseline. HTTPS acceptance is running.

Consolidation complete: both live databases report only successful Flyway version 1. All five HTTPS acceptance scenarios passed against the recreated deployment. Both services are healthy, and the disposable verification project was removed.
