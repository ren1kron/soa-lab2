# Native Helios Deployment Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task.

**Goal:** Run both APIs and the complete frontend directly on the user's Helios account without Docker or Node on Helios.

**Architecture:** Payara 6 serves the organizations WAR at `/` and a static React WAR at `/client/`; WildFly 35 serves the manager WAR. Both terminate HTTPS on loopback, accessed through SSH forwarding. PostgreSQL `pg:5432/studs` uses separate schemas owned by `s409411`.

**Tech Stack:** FreeBSD 14.5 amd64, installed OpenJDK 17, Payara 6.2025.11, WildFly 35.0.1, PostgreSQL, locally built React/Vite assets.

## Constraints and decisions

- The user selected deployment of the entire application, including the frontend, on Helios.
- Clear inherited `_JAVA_OPTIONS` before Java commands; explicitly configure each runtime's heap/metaspace budget. Memory settings are starting budgets, not measured guarantees.
- Preserve HTTPS, certificate/hostname validation, disabled application HTTP listeners, both independent service builds, existing Docker behavior, and all API contracts.
- Build WARs/assets on the user's computer; install distributions under the user's home without sudo.
- No migration changes or resets of the user's database. Create dedicated schemas; never overwrite unrelated data. Local validation uses disposable PostgreSQL fixtures.
- Existing Node proxy mode remains the frontend default. Static mode uses explicit HTTPS API origins and existing CORS support.
- Git metadata is read-only in this environment; work in the current authorized workspace and retain a backup under `.build-cache/helios-native/`.

## Review focus

- Inherited JVM flags must not silently undo the configured budgets.
- Static client URLs must use browser-visible SSH ports, while the manager uses Helios-local ports.
- Shared database schemas must keep the two Flyway histories separate.
- Configuration must disable HTTP before first startup, including default listener exposure.
- Certificates must match localhost, and the frontend must explain browser trust for both self-signed certificates.

## Task 1: Static client deployment

- [x] Add a failing native Node test for proxy/default and explicit HTTPS API URL selection, including invalid origins.
- [x] Implement the URL helper and use build-time `VITE_ORGANIZATIONS_URL` / `VITE_MANAGER_URL` in `frontend/src/api.ts`.
- [x] Run client tests and both ordinary and `/client/` production builds; package the latter as `client.war`.

## Task 2: Native runtime configuration

- [x] Add behavior tests for the Payara offline configurator: first-start memory settings, HTTPS binding, disabled HTTP/data grid/unneeded listeners, idempotence, and invalid input.
- [x] Implement a JDK-only Payara XML configurator and native shell launch/setup commands under `deploy/helios/`.
- [x] Configure WildFly with its embedded CLI before deployment; preserve upstream TLS verification.
- [x] Verify shell syntax, configuration tests, and runtime configuration against the pinned server distributions. Local Linux native-process validation passes; remote FreeBSD execution remains unverified.

## Task 3: Deployment guide and verification

- [x] Add `docs/helios-native.md` with local builds, runtime transfer/install, PostgreSQL schemas, private configuration, certificates, startup/shutdown, SSH access, browser trust, updates, and memory diagnostics.
- [x] Link the guide from README; preserve AGENTS.md.
- [x] Run all changed-component checks and obtain one independent final review. Record actual results below.

## Progress

- Frontend: `npm test` passes 3 tests; default and `/client/` builds succeed.
- Native helpers: `python3 -m unittest discover -s tests -p helios_native_test.py` passes 5 tests; shell syntax and `git diff --check` pass.
- Actual pinned Payara/WildFly distributions start with the native launch helpers, independent of Docker image entrypoints. Local validation uses a Linux runner, PostgreSQL fixture, and an init process that reaps orphaned server JVMs; it does not establish FreeBSD compatibility or a Helios memory guarantee.
- HTTPS acceptance: all 5 scenarios pass with both histories in separate schemas, including after restarting the services. Static HTML/assets and browser CORS preflight were also checked.
- Independent review found one shutdown issue: an unavailable WildFly CLI prevented Payara shutdown. Fixed by attempting both shutdowns and returning aggregate status; regression verified RED→GREEN and all 5 helper tests pass.
- Fresh Payara domains can omit JMS configuration; the helper handles its absence. Remote OSGi shell is disabled while retaining required local bundles and Java module options.
- Native application bundle prepared at `.build-cache/helios-native/soa-lab2-native.tgz` (19.5 MiB), containing 3 WARs, deployment helpers and guide; no credentials, private keys or runtimes.
