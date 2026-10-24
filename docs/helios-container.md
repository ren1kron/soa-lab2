# Deployment with helios-container

This guide starts the two API services and PostgreSQL using an existing [helios-container installation](https://github.com/RedGry/helios-container). Commands are labelled by machine. Replace `USERNAME` with your Helios login. No administrator access on Helios is needed for these commands.

The current `compose.yaml` contains the API services and database. It does not contain the React frontend; frontend deployment is a separate step. The backend URLs below can be used from a browser, curl, or the client's HTTPS proxy.

## 1. Check the VM on Helios

```sh
. "$HOME/.profile"
helios-container status
docker info
docker compose version
zfs get "userquota@$USER,userused@$USER" /home/studs
```

The override requires Compose 2.24.4+ for `!override`. It replaces the original port bindings rather than adding conflicting bindings. See [Docker's merge documentation](https://docs.docker.com/reference/compose-file/merge/).

The default 1 GiB VM is a tight fit for Payara, WildFly and PostgreSQL together. If your account permits it, start with:

```sh
helios-container stop
helios-container configure --memory 3072 --cpus 4
helios-container start
helios-container configure --auto-stop off
```

These settings affect your whole VM. Account limits can reject the memory allocation. The virtual disk size does not increase your Helios storage quota. Our current local application-server and PostgreSQL images total about 912 MiB uncompressed, before the VM, Docker storage overhead and writable data. A 1 GiB home quota is therefore a serious capacity constraint: arrange sufficient storage or smaller runtime images before importing them. [Upstream resource guidance](https://github.com/RedGry/helios-container/blob/main/docs/optimizations.md).

## 2. Build the images on your own computer

Run from this repository's root with Docker Desktop/Engine running:

```sh
docker buildx build --platform linux/amd64 --load \
  -t soa-lab2-organizations:helios \
  -f organizations-service/Dockerfile .

docker buildx build --platform linux/amd64 --load \
  -t soa-lab2-orgmanager:helios \
  -f orgmanager-service/Dockerfile .
```

Both images must be Linux/amd64, including when building on an Apple Silicon Mac. Building on your computer avoids Gradle compilation inside the emulated Helios VM.

The Helios override reuses the organizations image for certificate initialization: it already contains Java/keytool. There is no separate Gradle image to download into the VM.

## 3. Transfer the images from your computer

First establish a normal SSH connection once, so authentication and host-key acceptance are complete:

```sh
ssh -p 2222 USERNAME@se.ifmo.ru
```

Return to your computer's terminal and stream the images:

```sh
docker image save soa-lab2-organizations:helios soa-lab2-orgmanager:helios \
  | gzip -1 \
  | ssh -p 2222 USERNAME@se.ifmo.ru '"$HOME/.local/bin/docker" load'
```

The `docker` executable on Helios forwards commands and stdin to Docker inside the VM. The explicit path works in a noninteractive SSH session. Streaming avoids keeping a second image archive in your Helios home directory. The images themselves still consume your VM's disk and account quota.

## 4. Transfer the deployment files

On your computer, from the repository root:

```sh
tar -czf /tmp/soa-lab2-deploy.tgz \
  compose.yaml compose.helios.yaml \
  deploy/certificates.sh deploy/databases.sql

scp -P 2222 /tmp/soa-lab2-deploy.tgz USERNAME@se.ifmo.ru:soa-lab2-deploy.tgz
```

On Helios:

```sh
mkdir -p "$HOME/soa-lab2"
tar -xzf "$HOME/soa-lab2-deploy.tgz" -C "$HOME/soa-lab2"
helios-container ssh 'mkdir -p /workspace'
helios-container upload "$HOME/soa-lab2" /workspace
```

The resulting project is `/workspace/soa-lab2` **inside Linux**. Compose files, bind mounts, `.env` files and `docker compose cp` destinations refer to VM paths, not your FreeBSD home directory. [Upstream file/Compose guide](https://github.com/RedGry/helios-container/blob/main/docs/guide.md).

## 5. Start the services from Helios

```sh
docker image ls
docker pull postgres:17.6-alpine

docker compose \
  -f /workspace/soa-lab2/compose.yaml \
  -f /workspace/soa-lab2/compose.helios.yaml \
  config

docker compose \
  -f /workspace/soa-lab2/compose.yaml \
  -f /workspace/soa-lab2/compose.helios.yaml \
  up -d --no-build --pull never --wait --wait-timeout 1800
```

The two `:helios` image tags must appear in `docker image ls` before startup. Always pass both Compose files. `--no-build` avoids compilation; `--pull never` keeps the loaded application images and the explicitly pulled PostgreSQL image.

The override publishes 26125/26126 on the VM's network interface for QEMU forwarding. Payara still listens only for HTTPS on 8181; WildFly uses HTTPS on 8443. PostgreSQL and administration ports remain unpublished. Certificate and database data live in Docker volumes.

Startup may take several minutes. The extended health-check budgets and WildFly management timeout accommodate slower startup; they do not guarantee performance for a particular account.

## 6. Forward the ports on Helios

```sh
helios-container forward 26125 28125
helios-container forward 26126 28126
helios-container status
```

28125/28126 are example free ports on Helios. Use the actual ports printed by the commands. If one is unavailable, omit that command's second argument to choose a free port automatically, then substitute the result in the next step.

## 7. Open the SSH tunnel on your computer

```sh
ssh -p 2222 -N -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:36125:127.0.0.1:28125 \
  -L 127.0.0.1:36126:127.0.0.1:28126 \
  USERNAME@se.ifmo.ru
```

Leave this terminal open. Local ports 36125/36126 avoid the repository's usual local Docker ports 26125/26126.

| Service | Computer | Helios | VM | Container |
| --- | --- | --- | --- | --- |
| Organizations | localhost:36125 | 127.0.0.1:28125 | 26125 | 8181 |
| Manager | localhost:36126 | 127.0.0.1:28126 | 26126 | 8443 |

Use `https://localhost:36125` and `https://localhost:36126`. The tunnel preserves TLS all the way to the application servers. The self-signed certificates already include `localhost`.

The kit's optional web gateway accepts HTTP upstreams. Do not point it directly at these HTTPS ports; this guide uses TCP forwarding instead. [Gateway limitations](https://github.com/RedGry/helios-container/blob/main/docs/web.md).

## 8. Export public certificates and verify HTTPS

On Helios:

```sh
docker compose -f /workspace/soa-lab2/compose.yaml -f /workspace/soa-lab2/compose.helios.yaml \
  cp organizations-service:/run/tls/server.crt /workspace/soa-lab2/organizations.crt
docker compose -f /workspace/soa-lab2/compose.yaml -f /workspace/soa-lab2/compose.helios.yaml \
  cp orgmanager-service:/run/tls/server.crt /workspace/soa-lab2/manager.crt

helios-container download /workspace/soa-lab2/organizations.crt "$HOME/soa-lab2/organizations.crt"
helios-container download /workspace/soa-lab2/manager.crt "$HOME/soa-lab2/manager.crt"
```

On your computer, in another terminal with the tunnel still running:

```sh
mkdir -p .local/certs
scp -P 2222 USERNAME@se.ifmo.ru:soa-lab2/organizations.crt .local/certs/helios-organizations.crt
scp -P 2222 USERNAME@se.ifmo.ru:soa-lab2/manager.crt .local/certs/helios-manager.crt

curl --cacert .local/certs/helios-organizations.crt \
  'https://localhost:36125/organizations?size=1'

curl -i --cacert .local/certs/helios-manager.crt -X POST \
  'https://localhost:36126/orgmanager/hire/0?employeeId=0'
```

The first request should return XML with HTTP 200. The second deliberately uses an invalid ID and should return an XML validation error with HTTP 400 without changing data. Browser access requires trusting/accepting the self-signed certificates.

## Logs, restart and schema resets

On Helios, use both `-f` arguments with all Compose commands:

```sh
docker compose -f /workspace/soa-lab2/compose.yaml -f /workspace/soa-lab2/compose.helios.yaml ps
docker compose -f /workspace/soa-lab2/compose.yaml -f /workspace/soa-lab2/compose.helios.yaml \
  logs --tail 100 organizations-service orgmanager-service postgres
docker stats --no-stream
```

If readiness fails, inspect logs before restarting. An `HttpTimeoutException` from the Java health probe may require increasing the probe's own three-second timeout in `deploy/HttpsHealthCheck.java`, rebuilding both images on your computer and loading them again. Docker's health-check timeout does not override that internal timeout. WildFly deployment timeouts and memory errors are separate failure modes.

To apply updated images, repeat the local build/load steps and run the same `up` command. Ordinary `down` preserves data and certificates. For the repository's single-migration convention, reset the database after changing either V1 migration:

```sh
docker compose -f /workspace/soa-lab2/compose.yaml -f /workspace/soa-lab2/compose.helios.yaml down
docker volume rm soa-lab2_postgres-data
docker compose -f /workspace/soa-lab2/compose.yaml -f /workspace/soa-lab2/compose.helios.yaml \
  up -d --no-build --pull never --wait --wait-timeout 1800
```

This deletes both services' database contents and keeps their TLS volumes. After a Helios reboot, invoke `docker` to start the VM, bring the stack up and check readiness again. If applications started before PostgreSQL was ready, restart the two application services after the database is healthy.

These instructions are based on the current kit documentation and this repository's configuration. The Compose merge was checked locally; deployment on your Helios account still depends on its resources and available ports.
