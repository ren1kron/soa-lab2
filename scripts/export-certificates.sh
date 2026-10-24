#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .local/certs
docker compose cp organizations-service:/run/tls/server.crt .local/certs/organizations.crt
docker compose cp orgmanager-service:/run/tls/server.crt .local/certs/manager.crt
