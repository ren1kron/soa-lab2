# Deploy the native Helios bundle

The public site for this account is **https://se.ifmo.ru/~s409411/soa/**. The archive contains two backend WARs and a standalone frontend. Keep the archive in `~/soa/soa-lab2/` and extract it outside `public_html`.

## First installation

Follow `helios-native.md` inside the archive for the runtime downloads, private database configuration and local API checks. Once configured, run these commands in `~/soa/soa-lab2/soa-lab2-native` on Helios.

```sh
sh deploy/helios/run.sh organizations
nohup sh deploy/helios/run.sh manager > logs/manager-console.log 2>&1 < /dev/null &
sh deploy/helios/run.sh publish
```

The publisher installs the site in `~/public_html/soa/`. Hosted PHP must be able to reach the loopback HTTPS services. Visitors can use all application operations, including writes and deletes.

## Update an existing installation

On your computer, build and upload the new archive.

```sh
sh scripts/build-helios.sh
scp -P 2222 artifacts/helios/soa-lab2-native.tgz s409411@se.ifmo.ru:soa/soa-lab2/
```

On Helios, switch to the existing installation.

```sh
cd ~/soa/soa-lab2/soa-lab2-native
```

**Once when migrating from the client WAR**, install the new updater so it recognizes the standalone frontend.

```sh
tar -xOf ../soa-lab2-native.tgz deploy/helios/run.sh > deploy/helios/run.sh.new && \
  mv deploy/helios/run.sh.new deploy/helios/run.sh
```

Then apply the update. This restarts both services and publishes the site. Your existing database, settings and certificates are reused.

```sh
sh deploy/helios/run.sh update ../soa-lab2-native.tgz
tail -f logs/manager-console.log
```

Once WildFly deploys `ROOT.war`, press Ctrl+C to stop `tail`. From your computer with the visitor tunnel closed, check both public routes.

```sh
site='https://se.ifmo.ru/~s409411/soa'
curl -si "$site/api.php/organizations/organizations?size=1"
curl -si -X POST "$site/api.php/manager/orgmanager/hire/0?employeeId=0"
```

Expect XML with status 200 for the list and 400 for the zero IDs. See step 7 in `helios-native.md` for hosting and certificate diagnostics.
