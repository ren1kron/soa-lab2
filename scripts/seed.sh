#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

case "${1:-docker}" in
    docker)
        cd "$project_dir"
        docker compose exec -T postgres psql -X -U organizations -d organizations \
            -v ON_ERROR_STOP=1 -v schema=public < scripts/seed.sql
        ;;
    helios)
        config=${2:-"$project_dir/helios.env"}
        case "$config" in
            /*) ;;
            *) config="$PWD/$config" ;;
        esac
        . "$config"
        PGPASSWORD="$DATABASE_PASSWORD" psql -X \
            -h "$DATABASE_HOST" -p "$DATABASE_PORT" \
            -U "$DATABASE_USER" -d "$DATABASE_NAME" \
            -v ON_ERROR_STOP=1 -v schema="$DATABASE_USER" -f "$project_dir/scripts/seed.sql"
        ;;
    *)
        exit 1
        ;;
esac
