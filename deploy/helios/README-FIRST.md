# Deploy the project bundle on Helios

`SHA256SUMS` and `manifest.json` describe this project bundle. Payara 6.2025.11 and WildFly 35.0.1 are installed once; project updates never download, modify, or reinstall them. These commands assume the installation is in `~/soa/soa-lab2/soa-lab2-native`.

## Update an existing installation

On your computer:

```sh
sh scripts/build-helios.sh
scp -P 2222 artifacts/helios/soa-lab2-native.tgz s409411@se.ifmo.ru:soa/soa-lab2/
```

On Helios:

```sh
cd ~/soa/soa-lab2/soa-lab2-native
sh deploy/helios/run.sh update ../soa-lab2-native.tgz
tail -f logs/manager-console.log
```

Once `ROOT.war` is deployed, press Ctrl+C to stop `tail`. `update` checks the bundle while the current version is still running, stops both servers, replaces the application WARs and public deployment helpers, and starts both servers again. Your `helios.env`, certificates, domains, runtimes, and database are not touched, and a bundle containing anything else is refused. Do not delete the installation directory to update it, because those files live there.

**First update from an installation without the `update` command:** unpack the bundle over the installation once, then start normally. This also leaves `runtimes/` alone, and later updates can use the command above.

```sh
cd ~/soa/soa-lab2/soa-lab2-native
sh deploy/helios/run.sh stop
tar -xzf ../soa-lab2-native.tgz
sh deploy/helios/run.sh organizations
nohup sh deploy/helios/run.sh manager > logs/manager-console.log 2>&1 < /dev/null &
```

`run.sh stop` reports an error for any server that was not running. That is harmless here.

Ordinary application changes need no database reset. If a V1 migration changed, stop the services and drop only that service's objects before restarting. Never drop the `s409411` schema itself: it is your only schema and may hold other work.

```sql
-- Organizations
DROP TABLE employees, organizations, organizations_schema_history;
DROP TYPE organization_type;
-- Manager
DROP TABLE acquisition_journal, manager_schema_history;
```

## First installation

Install the Payara and WildFly distributions according to the bundled `helios-native.md`. Unpack this archive into `~/soa/soa-lab2/soa-lab2-native`, copy `deploy/helios/helios.env.example` to `helios.env`, run `chmod 600 helios.env`, and fill in the database and local admin passwords. Then run:

```sh
sh deploy/helios/run.sh configure
sh deploy/helios/run.sh organizations
nohup sh deploy/helios/run.sh manager > logs/manager-console.log 2>&1 < /dev/null &
```

## Access from your computer

Keep this SSH tunnel open:

```sh
ssh -p 2222 -N -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:36125:127.0.0.1:26125 \
  -L 127.0.0.1:36126:127.0.0.1:26126 \
  s409411@se.ifmo.ru
```

Trust both self-signed certificates at `https://localhost:36125/organizations?size=1` and `https://localhost:36126/` (a 404 at the second address is expected). Open **https://localhost:36125/client/**. The frontend already uses browser-visible API ports 36125/36126.
