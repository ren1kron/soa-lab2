#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
project="soa-lab2-test-$$"
export ORGANIZATIONS_HTTPS_PORT=0 MANAGER_HTTPS_PORT=0
cleanup() { docker compose -p "$project" --profile test down --volumes; }
trap cleanup EXIT INT TERM
docker compose -p "$project" up -d --build --wait --wait-timeout 240
for service in organizations-service orgmanager-service; do
  database=organizations
  if [ "$service" = orgmanager-service ]; then database=orgmanager; fi
  docker run --rm --network "${project}_default" \
    -e "TEST_DATABASE_URL=jdbc:postgresql://postgres:5432/$database" \
    -v "$PWD:/work" -w "/work/$service" gradle:8.14.3-jdk17 \
    gradle -g /work/.build-cache/test-gradle --no-daemon spotlessCheck test integrationTest --rerun-tasks
done
docker compose -p "$project" --profile test run --rm acceptance
mkdir -p .build-cache
recovery_sql=".build-cache/$project-recovery.sql"
docker compose -p "$project" --profile test run --rm --no-deps --entrypoint python acceptance /tests/recovery.py prepare > "$recovery_sql"
docker compose -p "$project" exec -T postgres psql -U orgmanager -d orgmanager -v ON_ERROR_STOP=1 < "$recovery_sql"
docker compose -p "$project" restart organizations-service orgmanager-service
docker compose -p "$project" up -d --wait --wait-timeout 240
docker compose -p "$project" --profile test run --rm --no-deps --entrypoint python acceptance /tests/recovery.py verify
docker compose -p "$project" --profile test run --rm acceptance
