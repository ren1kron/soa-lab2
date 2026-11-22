#!/bin/sh
# Invoked by run.sh with the installation path and service ports from helios.env.
set -eu
umask 077
frontend="$SOA_HOME/artifacts/frontend"
target="$HOME/public_html/soa"

for port in "$ORGANIZATIONS_PORT" "$MANAGER_PORT"; do
    case "$port" in ''|*[!0-9]*) echo "Invalid service port: $port" >&2; exit 1 ;; esac
    [ "$port" -ge 1 ] && [ "$port" -le 65535 ] || { echo "Invalid service port: $port" >&2; exit 1; }
done

# Keep existing pages and their permissions. Only this application's directory is managed.
if [ ! -d "$HOME/public_html" ]; then mkdir -m 755 "$HOME/public_html"; fi
if [ ! -e "$target" ]; then mkdir -m 755 "$target"; fi
if [ -L "$target" ] || [ ! -d "$target" ] || { [ ! -f "$target/.soa-lab2" ] && [ -n "$(ls -A "$target")" ]; }; then
    echo "Refusing to overwrite $target. Move the existing site aside before publishing." >&2
    exit 1
fi
touch "$target/.soa-lab2"
stage=$(mktemp -d "$target/.publish.XXXXXX")
trap 'rm -rf "$stage"' EXIT

install -m 644 "$frontend/index.html" "$stage/index.html"
mkdir "$stage/assets"
for asset in "$frontend"/assets/*; do
    install -m 644 "$asset" "$stage/assets/"
done
for service in organizations manager; do
    install -m 644 "$SOA_HOME/tls/$service/server.crt" "$stage/$service.crt"
done
sed -e "s/@ORGANIZATIONS_PORT@/$ORGANIZATIONS_PORT/g" -e "s/@MANAGER_PORT@/$MANAGER_PORT/g" \
    "$SOA_HOME/deploy/helios/api.php" > "$stage/api.php"
chmod 644 "$stage/api.php"

# Publish the entry page last. Previous hashed assets can still serve open tabs.
if [ ! -d "$target/assets" ]; then mkdir -m 755 "$target/assets"; fi
mv -f "$stage"/assets/* "$target/assets/"
mv -f "$stage"/*.crt "$stage/api.php" "$target/"
mv -f "$stage/index.html" "$target/index.html"
echo "Published $target"
