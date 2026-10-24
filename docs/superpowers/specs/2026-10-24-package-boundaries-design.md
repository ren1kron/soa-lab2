# Package boundaries design

The user selected option 1 from the reviewed architecture proposal: Java packages within each existing independent Gradle project. This is a responsibility refactor of the existing services, with the OpenAPI contracts and deployed behavior retained.

## Approach

Package separation was selected over four Gradle submodules per service because each service is small and the additional build configuration is unnecessary. Each service continues to produce one WAR with its own wrapper and build.gradle.kts.

Both services use config, web, application, infrastructure, and generated namespaces. Web owns request validation, XML, response mapping and status codes. Application owns operations and persistence/gateway interfaces and imports no web, infrastructure, JDBC, Jackson, or framework APIs. Infrastructure implements the interfaces. Config is the composition root.

## Organizations

Replace OrganizationStore with OrganizationService and EmployeeService, OrganizationRepository and EmployeeRepository interfaces, JDBC implementations, and WriteTransaction. Keep the advisory lock and all related checks/writes inside the same transaction. Return typed generated models and PageResult to controllers. Split collection request parsing from query execution; preserve numeric bounds, ordering, filtering and pagination.

## Manager

Split ManagerWorkflow into HireEmployeeService and AcquireOrganizationService. Keep OrganizationsGateway and AcquisitionJournal as application interfaces. AcquisitionSnapshot stores the same durable employee ID list; AcquisitionOutcome is an internal result mapped to generated response models at the HTTP boundary. Split upstream XML reading/error translation from response XML writing. Separate database, TLS/client, and application configuration.

## Constraints and verification

Generated public models remain generated, normalized under generated.api, generated.model and generated.upstream.model. Do not introduce a shared build, new service, schema migration or hand-maintained public DTO hierarchy. AGENTS.md remains untouched.

Tests mirror source packages. Database tests move to src/integrationTest; reusable test fixtures have an explicit support package. Architecture tests enforce the package dependencies. Existing behavior tests, real PostgreSQL concurrency/recovery tests, five Docker acceptance scenarios and restart recovery remain the acceptance gates.
