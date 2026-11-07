# Native deployment on Helios

This deploys **both APIs and the frontend on Helios**, without Docker, a Linux VM, Node, or administrator access on the server. It targets your confirmed FreeBSD 14.5 amd64 / OpenJDK 17 account, `s409411`, and PostgreSQL `pg:5432/studs`.

Payara 6.2025.11 serves the organizations API and the built frontend at `/client/`. WildFly 35.0.1 serves the manager API. PostgreSQL uses your personal schema in the existing database, shared by both services. The manager continues to call the first service through certificate-verified HTTPS.

## 1. Prepare the application on your computer

Use **JDK 17**, **Node 24+**, npm, and Python 3 locally. From this repository's root:

```sh
sh scripts/build-helios.sh
```

On macOS the script selects JDK 17 automatically when `JAVA_HOME` is unset. Otherwise point `JAVA_HOME` at a JDK 17 installation. The script resolves paths relative to itself, so it also works when called from another directory.

To compile the Java services using the pinned Gradle Docker image on your computer instead:

```sh
sh scripts/build-helios.sh --docker
```

The script runs the bundle/helper tests, rebuilds both independent services with `clean build` (including JUnit and Spotless), installs frontend dependencies from the lockfile, runs frontend tests, builds the static client, and writes:

- `artifacts/helios/soa-lab2-native.tgz` — all three WARs, public deployment helpers, and this guide.
- `artifacts/helios/SHA256SUMS` — the project bundle's SHA-256.
- `artifacts/helios/manifest.json` — build time, source state, successful commands, and bundle metadata.
- `artifacts/helios/README-FIRST.md` — installation/update instructions.

It packages the frontend for `/client/`, with browser-visible API origins `https://localhost:36125` and `https://localhost:36126`. It includes current workspace changes, including uncommitted files. A failed build leaves the last completed bundle in place. PostgreSQL integration/acceptance checks remain a separate `sh scripts/test.sh` run.

The builder does **not download, rebuild, or change Payara/WildFly distributions**. Existing server ZIPs in the output directory are preserved. After the initial server installation, copy only the new `soa-lab2-native.tgz` to Helios for project updates. The archive contains no passwords, generated certificates, runtimes, or build caches.

## 2. Download and transfer the server distributions once

Skip these downloads if you already have the two server ZIPs from the prepared deployment. Otherwise, on your computer:

```sh
mkdir -p artifacts/helios
curl -fL -o artifacts/helios/payara-6.2025.11.zip \
  https://repo.maven.apache.org/maven2/fish/payara/distributions/payara/6.2025.11/payara-6.2025.11.zip
curl -fL -o artifacts/helios/wildfly-35.0.1.Final.zip \
  https://repo.maven.apache.org/maven2/org/wildfly/wildfly-dist/35.0.1.Final/wildfly-dist-35.0.1.Final.zip

ssh -p 2222 s409411@se.ifmo.ru 'mkdir -p soa/soa-lab2'
scp -P 2222 artifacts/helios/soa-lab2-native.tgz \
  artifacts/helios/payara-6.2025.11.zip \
  artifacts/helios/wildfly-35.0.1.Final.zip \
  s409411@se.ifmo.ru:soa/soa-lab2/
```

For manual SFTP transfer, upload those three files into `~/soa/soa-lab2` on Helios. Use the **server distribution ZIPs**, not source archives. The pinned versions match the repository's Docker deployments. [Payara artifact](https://central.sonatype.com/artifact/fish.payara.distributions/payara/6.2025.11), [WildFly release](https://github.com/wildfly/wildfly/releases/tag/35.0.1.Final).

## 3. Unpack on Helios

```sh
ssh -p 2222 s409411@se.ifmo.ru
```

Stop the existing VM if it is running, to release its memory:

```sh
helios-container stop
```

Then install into a new directory:

```sh
mkdir -p ~/soa/soa-lab2/soa-lab2-native/runtimes
tar -xzf ~/soa/soa-lab2/soa-lab2-native.tgz -C ~/soa/soa-lab2/soa-lab2-native
tar -xf ~/soa/soa-lab2/payara-6.2025.11.zip -C ~/soa/soa-lab2/soa-lab2-native/runtimes
tar -xf ~/soa/soa-lab2/wildfly-35.0.1.Final.zip -C ~/soa/soa-lab2/soa-lab2-native/runtimes
cd ~/soa/soa-lab2/soa-lab2-native
```

FreeBSD's `tar` can unpack the ZIP distributions. The expected paths are `runtimes/payara6/bin/asadmin` and `runtimes/wildfly-35.0.1.Final/bin/standalone.sh`. If you already have those exact distributions, set `PAYARA_HOME` and `WILDFLY_HOME` in the next step instead of installing duplicates.

Once extraction succeeds, the three uploaded archives in `~/soa/soa-lab2` can be removed to reclaim space. Stopping the old container VM releases memory but does not reclaim its disk allocation.

## 4. Set private configuration

```sh
cp deploy/helios/helios.env.example helios.env
chmod 600 helios.env
vi helios.env
```

Replace `DATABASE_PASSWORD` with your PostgreSQL password and choose a separate `PAYARA_ADMIN_PASSWORD`. Do not send either password here. Keep the supplied host/database/user and port settings unless a port is already occupied.

The defaults are:

| Purpose | Helios loopback port |
| --- | --- |
| Organizations API and frontend | 26125, HTTPS |
| Manager API | 26126, HTTPS |
| Payara administration | 26124 |
| WildFly administration | 26129 |
| WildFly recovery/status | 26130 / 26131 |

Verify your database connection before configuring the servers; `-W` prompts privately:

```sh
psql -X -h pg -p 5432 -U s409411 -d studs -W \
  -c 'SELECT current_user, current_database(), version();'
```

Your `psql` version identifies the client; the query above identifies the actual server version.

## 5. Configure the servers once, while stopped

```sh
sh deploy/helios/run.sh configure
```

This checks your database schema and creates a personal Payara domain, separate self-signed certificates, and the manager's truststore. Both services use the schema named after your account (`s409411`), because Helios does not allow creating others; no `CREATE SCHEMA`, `CREATE DATABASE`, role creation, or superuser rights are needed. Their tables have different names, and `FLYWAY_HISTORY_TABLE` gives each service its own Flyway history (`organizations_schema_history`, `manager_schema_history`). Other tables in the schema are untouched: `configure` changes nothing in the database and stops if `organizations`, `employees`, `organization_type`, or `acquisition_journal` already exists from something else. Rename or remove such objects yourself after checking what they are. [JDBC schema selection](https://jdbc.postgresql.org/documentation/use/).

The configurator disables the application HTTP listeners **before first startup**, binds service/admin listeners to loopback, disables Payara's data grid/IIOP/JMX listeners and remote OSGi shell, and avoids starting an embedded JMS broker. It retains the Payara JVM module options required by Java 17.

Each server starts with `-Xms64m -Xmx256m`, a 192 MiB metaspace ceiling, Serial GC, and two visible processors. The launch helper clears inherited `_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS`, and `JDK_JAVA_OPTIONS` for its own processes. Your reported `_JAVA_OPTIONS` would otherwise override these settings with a 1 GiB heap and 128 MiB metaspace.

These are starting budgets, **not total process memory limits or a guarantee of fitting a particular account quota**. Thread stacks, class metadata, code cache, and native buffers also consume memory. Payara settings are applied offline because changing JVM flags after boot would not fix its first startup. [Payara domain startup](https://docs.payara.fish/community/docs/6.2025.11/Technical%20Documentation/Payara%20Server%20Documentation/Command%20Reference/start-domain.html), [WildFly JVM configuration](https://docs.wildfly.org/35/Admin_Guide.html).

## 6. Start Payara, then WildFly

On Helios, from `~/soa/soa-lab2/soa-lab2-native`:

```sh
sh deploy/helios/run.sh organizations
curl --cacert tls/organizations/server.crt \
  'https://localhost:26125/organizations?size=1'
```

The first command starts the domain in the background and deploys `organizations.war` at `/` plus `client.war` at `/client/`. The API should return XML. Flyway applies the existing V1 migration in your schema and records it in `organizations_schema_history`.

Start the manager and keep its output in a log:

```sh
nohup sh deploy/helios/run.sh manager > logs/manager-console.log 2>&1 < /dev/null &
tail -f logs/manager-console.log
```

Once WildFly reports successful deployment of `ROOT.war`, press Ctrl+C to stop **tail**, then check:

```sh
curl -i --cacert tls/manager/server.crt -X POST \
  'https://localhost:26126/orgmanager/hire/0?employeeId=0'
```

Expect `400` with an XML validation error. The zero IDs intentionally test the manager without modifying data. Look for `ROOT.war.failed` and the accompanying log if deployment fails. The two services share a schema but must keep different `FLYWAY_HISTORY_TABLE` values; start them through `run.sh`, which sets these.

## 7. Open the frontend from your computer

Run in a separate terminal **on your computer** and leave it open:

```sh
ssh -p 2222 -N -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:36125:127.0.0.1:26125 \
  -L 127.0.0.1:36126:127.0.0.1:26126 \
  s409411@se.ifmo.ru
```

No `helios-container forward` command is involved. SSH connects directly to the native server ports and preserves end-to-end HTTPS.

First visit both addresses in your browser and accept/trust their respective self-signed certificates:

1. `https://localhost:36125/organizations?size=1`
2. `https://localhost:36126/` — a `404` after certificate acceptance is expected here.

Then open **https://localhost:36125/client/**. The frontend assets and both APIs now run on Helios; your computer only supplies the browser and SSH tunnel. No frontend development server is required.

The manager allows CORS from `https://localhost:36125`, as configured in `helios.env`. The frontend must use the **local tunnel ports** 36125/36126, whereas the manager-to-organizations call uses the **Helios port** 26125. Changing the browser ports requires rebuilding the static client with matching `VITE_*_URL` values and updating `CORS_ORIGINS`.

## Shutdown, updates, and diagnosis

Stop both servers on Helios:

```sh
cd ~/soa/soa-lab2/soa-lab2-native
sh deploy/helios/run.sh stop
```

This preserves the database objects, applications, configuration, and certificates. Do not rerun certificate/configuration setup against running servers.

To update the project, rebuild and upload only the bundle, then run `update` on Helios. This also covers frontend changes, because the client is part of the bundle:

```sh
# On your computer
sh scripts/build-helios.sh
scp -P 2222 artifacts/helios/soa-lab2-native.tgz s409411@se.ifmo.ru:soa/soa-lab2/

# On Helios
cd ~/soa/soa-lab2/soa-lab2-native
sh deploy/helios/run.sh update ../soa-lab2-native.tgz
tail -f logs/manager-console.log
```

`update` checks the bundle before stopping anything, then stops both servers, replaces `artifacts/`, `deploy/helios/`, and this guide, and starts both servers again. It does not touch `runtimes/`, `domains/`, `tls/`, `private/`, `logs/`, or `helios.env`, and it refuses a bundle containing files for any of them. Never delete the installation directory to update it. If an installation's `run.sh` has no `update` command yet, follow the first-update steps in `README-FIRST.md` once. If `update` reports that server configuration changed, run `stop`, then `configure`, then start both services as in step 6.

Useful diagnostics:

```sh
tail -n 100 domains/organizations/logs/server.log
tail -n 100 logs/manager-console.log
ps -u "$USER" -o pid,rss,command
sockstat -4 -l -u "$USER"
```

`rss` is resident memory in KiB. If a log reports `OutOfMemoryError: Metaspace`, increase that server's metaspace ceiling; increasing `-Xmx` does not increase metaspace. If it reports `Java heap space`, increase that server's heap. Payara's settings are in `domains/organizations/config/domain.xml`; WildFly's are in `deploy/helios/run.sh`. Stop the affected server before changing them. A bare `Killed` without a JVM error can indicate an external account/process limit; collect the logs and memory readings before increasing allocations.

For a port collision, stop the servers, choose free ports in `helios.env`, rerun `configure`, and update the remote ends of the SSH forwarding command. Browser ports may remain 36125/36126. For a storage quota error, inspect the space used by the old container VM and runtime archives; do not erase a VM containing data you still need.

This guide does not import data from the earlier container databases. It creates fresh tables in your schema on the university database; retain the old deployment until any needed data has been exported.
