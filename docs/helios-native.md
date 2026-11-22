# Native deployment on Helios

Visitors open **https://se.ifmo.ru/~s409411/soa/**. Helios serves the built React files from `~/public_html/soa/`. A PHP gateway in the same directory sends XML requests over verified HTTPS to Payara (organizations and employees) or WildFly (manager). The Java services and their administration ports stay on loopback. Both services use the account’s personal schema in `pg:5432/studs`.

This guide targets the native FreeBSD 14.5 / JDK 17 installation. The public web host must execute PHP for `api.php/…` paths, support PHP HTTPS streams (OpenSSL and `allow_url_fopen`), and allow them to reach the Helios IPv4 loopback ports. The public checks in step 7 exercise those requirements. The hosting pattern is also used by the [Helios web gateway](https://github.com/RedGry/helios-container/blob/main/docs/web.md).

The application has no login. Publishing the gateway lets anyone with access to the site create, edit and delete records and run manager operations. Use demo data for a public deployment. For restricted use, have the web host enforce access on the whole `/~s409411/soa/` path, including `api.php`.

**Already running the tunnel-based release?** Follow [the one-time migration](#migrate-the-previous-frontend-war-installation) after building and uploading the new archive. Your existing database, keys and runtime installations can be reused.

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

The script runs the bundle/helper tests, rebuilds both independent services with `clean build` (including JUnit and Spotless), installs frontend dependencies from the lockfile, runs frontend tests and builds the static client. The output includes

- `artifacts/helios/soa-lab2-native.tgz` — the two API WARs, the standalone client in `artifacts/frontend/`, the PHP gateway template, deployment helpers and this guide.
- `artifacts/helios/SHA256SUMS` — the project bundle's SHA-256.
- `artifacts/helios/manifest.json` — build time, source state, successful commands, and bundle metadata.
- `artifacts/helios/README-FIRST.md` — installation/update instructions.

The gateway integration test runs when PHP CLI and OpenSSL are available on the build computer. It exercises the published PHP file against two HTTPS test services. The Helios build uses relative asset and API paths. The same bundle can be published under another Helios login. A failed build leaves the previous bundle in place. To run the separate PostgreSQL integration and acceptance checks, use `sh scripts/test.sh`.

Payara and WildFly distributions are installed once. After the initial installation, transfer just the new project bundle. Keep that archive and the installation directory outside `public_html`.

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

The Helios commands below use POSIX shell syntax. If your login shell is `csh` or `tcsh`, enter `sh` first.

If you previously used a Helios container VM solely for this application, you can stop it to release memory:

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
| Organizations and employees API | 26125, HTTPS |
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

The first command starts the domain in the background and deploys `organizations.war` at `/`. The API should return XML. Flyway applies the existing V1 migration in your schema and records it in `organizations_schema_history`.

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

## 7. Publish and verify the public link

On Helios, in `~/soa/soa-lab2/soa-lab2-native`, run:

```sh
sh deploy/helios/run.sh publish
```

This installs `index.html`, hashed JS/CSS assets, `api.php`, and **only the two public service certificates** in `~/public_html/soa/`. The gateway uses the ports from `helios.env` and verifies each upstream certificate and hostname. The database credentials, private keys, WARs and administration tools stay in the private installation. The publisher creates the site directory and installs publicly readable site files. If `~/public_html/soa` already contains a different site, move that site to a location of your choice before publishing.

Close any visitor SSH tunnels. From your computer, run:

```sh
site='https://se.ifmo.ru/~s409411/soa'
curl -fsS "$site/"                           # HTML referencing ./assets/…
curl -si "$site/api.php/organizations/organizations?size=1"
curl -si -X POST "$site/api.php/manager/orgmanager/hire/0?employeeId=0"
```

Expect `200` with XML and an `X-Total-Count` header for the collection, then `400` with an XML validation error for the zero IDs. These checks leave the database untouched. Open **https://se.ifmo.ru/~s409411/soa/** in the browser and switch between the organization and employee lists. Visitors use the university’s public HTTPS certificate.

| Symptom | What to check |
| --- | --- |
| The page or its JS/CSS returns 403 | Check traversal permissions on your home directory and read/traversal permissions on `public_html` and `soa`. Compare with a working public page in your account. |
| The page loads, but the API returns PHP source, an HTML error, or a hosting-level 404/405 | Check the full `api.php/…` URL, PHP execution, path-info handling and permitted methods with the hosting administrator. Static file hosting alone cannot relay API requests. |
| The gateway returns XML with `502 PROXY_ERROR` | Check both local HTTPS commands in step 6. Republish after changing ports or service certificates. If both local checks pass, verify that hosted PHP can open HTTPS streams to those ports from its network environment. |
| The gateway is unavailable on your hosting configuration | Ask the hosting administrator to route `/~s409411/soa/api.php/organizations/` to `https://127.0.0.1:26125/` and `/~s409411/soa/api.php/manager/` to `https://127.0.0.1:26126/` on the service host. Strip each prefix, preserve methods, bodies, query strings, status and pagination headers, and verify TLS with the corresponding public certificates. The frontend uses these same paths. |

## Migrate the previous frontend WAR installation

Build and upload the new archive as in the update commands below. On Helios, install the **new updater first**. The older updater expects `client.war` and will reject this bundle.

```sh
cd ~/soa/soa-lab2/soa-lab2-native
tar -xOf ../soa-lab2-native.tgz deploy/helios/run.sh > deploy/helios/run.sh.new && \
  mv deploy/helios/run.sh.new deploy/helios/run.sh
sh deploy/helios/run.sh update ../soa-lab2-native.tgz
```

During startup the new helper undeploys the old `client` application if Payara still lists it. The update installs both API WARs and publishes the new site using your existing `helios.env` and service certificates. Complete the public checks in step 7.

## Shutdown, updates, and diagnosis

For subsequent updates, run on your computer:

```sh
sh scripts/build-helios.sh
scp -P 2222 artifacts/helios/soa-lab2-native.tgz s409411@se.ifmo.ru:soa/soa-lab2/
```

Then run on Helios:

```sh
cd ~/soa/soa-lab2/soa-lab2-native
sh deploy/helios/run.sh update ../soa-lab2-native.tgz
tail -f logs/manager-console.log
```

The updater checks the bundle, restarts the two services and republishes the site. Once WildFly deploys `ROOT.war`, repeat the public checks. If publication fails, fix the reported problem and rerun `sh deploy/helios/run.sh publish` independently. If the updater reports a server configuration change, stop both services, rerun `configure`, start the services as in step 6 and republish.

To stop the APIs on Helios, run `sh deploy/helios/run.sh stop`. Static files remain visible until you move the published `soa` directory outside `public_html`.

Useful diagnostics:

```sh
tail -n 100 domains/organizations/logs/server.log
tail -n 100 logs/manager-console.log
ps -u "$USER" -o pid,rss,command
sockstat -4 -l -u "$USER"
```

`rss` is resident memory in KiB. If a log reports `OutOfMemoryError: Metaspace`, increase that server's metaspace ceiling; increasing `-Xmx` does not increase metaspace. If it reports `Java heap space`, increase that server's heap. Payara's settings are in `domains/organizations/config/domain.xml`; WildFly's are in `deploy/helios/run.sh`. Stop the affected server before changing them. A bare `Killed` without a JVM error can indicate an external account/process limit; collect the logs and memory readings before increasing allocations.

For a port collision, stop the servers, choose free ports in `helios.env`, rerun `configure`, start the services and rerun `publish` to update the gateway. For a storage quota error, inspect the space used by the old container VM and runtime archives; do not erase a VM containing data you still need.

This guide does not import data from the earlier container databases. It creates fresh tables in your schema on the university database; retain the old deployment until any needed data has been exported.
