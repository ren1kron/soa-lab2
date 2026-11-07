#!/bin/sh
set -eu
umask 077
script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
SOA_HOME=${SOA_HOME:-$(CDPATH= cd "$script_dir/../.." && pwd)}
export SOA_HOME
if [ ! -f "$SOA_HOME/helios.env" ]; then
    echo "Copy deploy/helios/helios.env.example to $SOA_HOME/helios.env and fill in the passwords." >&2
    exit 1
fi
. "$SOA_HOME/helios.env"
unset _JAVA_OPTIONS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
JAVA_HOME=${JAVA_HOME:-$(java -XshowSettings:properties -version 2>&1 | awk '/java.home =/ {print $3; exit}')}
AS_JAVA=$JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export JAVA_HOME AS_JAVA PATH
PAYARA_HOME=${PAYARA_HOME:-"$SOA_HOME/runtimes/payara6"}
WILDFLY_HOME=${WILDFLY_HOME:-"$SOA_HOME/runtimes/wildfly-35.0.1.Final"}
export ORGANIZATIONS_PORT MANAGER_PORT PAYARA_ADMIN_PORT WILDFLY_ADMIN_PORT MANAGER_RECOVERY_PORT MANAGER_STATUS_PORT
export DATABASE_USER DATABASE_PASSWORD APP_TIME_ZONE CORS_ORIGINS
AS_EXTRA_JAVA_OPTS='-Xms16m -Xmx128m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:ActiveProcessorCount=2'
export AS_EXTRA_JAVA_OPTS
JAVA_OPTS='-Xms64m -Xmx256m -Xss512k -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=32m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Djava.net.preferIPv4Stack=true -Djava.awt.headless=true'
export JAVA_OPTS
domains="$SOA_HOME/domains"
domain="$domains/organizations"
mkdir -p "$SOA_HOME/logs" "$SOA_HOME/private"

asadmin() {
    "$PAYARA_HOME/bin/asadmin" --host 127.0.0.1 --port "$PAYARA_ADMIN_PORT" \
        --user admin --passwordfile "$SOA_HOME/private/asadmin.password" "$@"
}

certificate() {
    directory="$SOA_HOME/tls/$1"
    mkdir -p "$directory"
    if [ ! -f "$directory/server.p12" ]; then
        keytool -J-Xmx64m -genkeypair -alias server -keyalg RSA -keysize 3072 -validity 3650 \
            -dname "CN=localhost,OU=SOA Lab 2" -ext 'SAN=dns:localhost,ip:127.0.0.1' \
            -storetype PKCS12 -keystore "$directory/server.p12" \
            -storepass localchangeit -keypass localchangeit -noprompt
    fi
    keytool -J-Xmx64m -exportcert -rfc -alias server -keystore "$directory/server.p12" \
        -storepass localchangeit -file "$directory/server.crt"
    chmod 644 "$directory/server.crt"
}

case "${1:-}" in
    configure)
        case "$DATABASE_PASSWORD:$PAYARA_ADMIN_PASSWORD" in
            *REPLACE_WITH*) echo 'Fill in both passwords in helios.env first.' >&2; exit 1 ;;
        esac
        for artifact in organizations.war orgmanager.war client.war; do
            test -f "$SOA_HOME/artifacts/$artifact" || { echo "Missing artifacts/$artifact" >&2; exit 1; }
        done
        test -x "$PAYARA_HOME/bin/asadmin"
        test -x "$WILDFLY_HOME/bin/jboss-cli.sh"
        PGPASSWORD="$DATABASE_PASSWORD" psql -X -h "$DATABASE_HOST" -p "$DATABASE_PORT" \
            -U "$DATABASE_USER" -d "$DATABASE_NAME" -v ON_ERROR_STOP=1 -f "$script_dir/check-schema.sql"
        printf 'AS_ADMIN_PASSWORD=%s\n' "$PAYARA_ADMIN_PASSWORD" > "$SOA_HOME/private/asadmin.password"
        if [ ! -f "$domain/config/domain.xml" ]; then
            asadmin create-domain --domaindir "$domains" --checkports=false --portbase 26000 organizations
        fi
        java -Xmx128m "$script_dir/ConfigurePayara.java" "$domain/config/domain.xml" \
            "$ORGANIZATIONS_PORT" "$PAYARA_ADMIN_PORT"
        certificate organizations
        certificate manager
        keytool -J-Xmx64m -importkeystore -noprompt -srckeystore "$SOA_HOME/tls/organizations/server.p12" \
            -srcstoretype PKCS12 -srcstorepass localchangeit -srcalias server \
            -destkeystore "$domain/config/keystore.p12" -deststoretype PKCS12 \
            -deststorepass changeit -destkeypass changeit -destalias server
        if keytool -J-Xmx64m -list -keystore "$SOA_HOME/tls/manager/upstream-trust.p12" \
            -storepass localchangeit -alias organizations >/dev/null 2>&1; then
            keytool -J-Xmx64m -delete -keystore "$SOA_HOME/tls/manager/upstream-trust.p12" \
                -storepass localchangeit -alias organizations
        fi
        keytool -J-Xmx64m -importcert -noprompt -alias organizations -storetype PKCS12 \
            -keystore "$SOA_HOME/tls/manager/upstream-trust.p12" -storepass localchangeit \
            -file "$SOA_HOME/tls/organizations/server.crt"
        "$WILDFLY_HOME/bin/jboss-cli.sh" --file="$script_dir/wildfly.cli"
        ;;
    organizations)
        DATABASE_URL="jdbc:postgresql://$DATABASE_HOST:$DATABASE_PORT/$DATABASE_NAME?currentSchema=$DATABASE_USER&connectTimeout=10"
        FLYWAY_HISTORY_TABLE=organizations_schema_history
        export DATABASE_URL FLYWAY_HISTORY_TABLE
        asadmin start-domain --domaindir "$domains" --timeout 600 organizations
        asadmin deploy --force=true --name organizations "$SOA_HOME/artifacts/organizations.war"
        asadmin deploy --force=true --name client --contextroot client "$SOA_HOME/artifacts/client.war"
        ;;
    manager)
        DATABASE_URL="jdbc:postgresql://$DATABASE_HOST:$DATABASE_PORT/$DATABASE_NAME?currentSchema=$DATABASE_USER&connectTimeout=10"
        FLYWAY_HISTORY_TABLE=manager_schema_history
        ORGANIZATIONS_URL="https://localhost:$ORGANIZATIONS_PORT"
        UPSTREAM_TRUSTSTORE="$SOA_HOME/tls/manager/upstream-trust.p12"
        UPSTREAM_TRUSTSTORE_PASSWORD=localchangeit
        export DATABASE_URL FLYWAY_HISTORY_TABLE ORGANIZATIONS_URL UPSTREAM_TRUSTSTORE UPSTREAM_TRUSTSTORE_PASSWORD
        # A stale .failed beside .dodeploy makes WildFly add the deployment twice and abort its boot.
        rm -f "$WILDFLY_HOME"/standalone/deployments/ROOT.war.*
        cp "$SOA_HOME/artifacts/orgmanager.war" "$WILDFLY_HOME/standalone/deployments/ROOT.war"
        touch "$WILDFLY_HOME/standalone/deployments/ROOT.war.dodeploy"
        exec "$WILDFLY_HOME/bin/standalone.sh" -b 127.0.0.1 -bmanagement 127.0.0.1
        ;;
    stop)
        result=0
        "$WILDFLY_HOME/bin/jboss-cli.sh" --connect --controller="127.0.0.1:$WILDFLY_ADMIN_PORT" --command=:shutdown || result=$?
        asadmin stop-domain --domaindir "$domains" organizations || result=$?
        exit "$result"
        ;;
    update)
        bundle=${2:-}
        if [ ! -f "$bundle" ]; then
            echo 'Usage: sh deploy/helios/run.sh update PATH/TO/soa-lab2-native.tgz' >&2
            exit 1
        fi
        staging=$(mktemp -d "$SOA_HOME/.update.XXXXXX")
        trap 'rm -rf "$staging"' EXIT
        # Everything is checked here, while the current version keeps running.
        tar -xf "$bundle" -C "$staging"
        for required in artifacts/organizations.war artifacts/orgmanager.war artifacts/client.war deploy/helios/run.sh; do
            if [ ! -s "$staging/$required" ]; then
                echo "Not a Helios bundle, missing $required; build it with scripts/build-helios.sh" >&2
                exit 1
            fi
        done
        foreign=$(cd "$staging" && find . ! -type d | grep -Ev '^\./(artifacts/|deploy/helios/|helios-native\.md$)' || true)
        if [ -n "$foreign" ]; then
            printf 'Refusing a bundle with files outside the project:\n%s\n' "$foreign" >&2
            exit 1
        fi
        reconfigure=
        for helper in ConfigurePayara.java wildfly.cli; do
            cmp -s "$staging/deploy/helios/$helper" "$script_dir/$helper" || reconfigure=1
        done
        sh "$script_dir/run.sh" stop || echo 'Continuing: a server that was not running cannot be stopped.'
        # Renaming gives new inodes, so the shell reading this script is not disturbed.
        (cd "$staging" && find . ! -type d) | while IFS= read -r file; do
            mkdir -p "$(dirname "$SOA_HOME/$file")"
            mv -f "$staging/$file" "$SOA_HOME/$file" || exit 1
        done
        sh "$script_dir/run.sh" organizations
        nohup sh "$script_dir/run.sh" manager > "$SOA_HOME/logs/manager-console.log" 2>&1 < /dev/null &
        echo "Updated. Follow the manager's startup with: tail -f $SOA_HOME/logs/manager-console.log"
        if [ -n "$reconfigure" ]; then
            echo 'Server configuration changed in this bundle. Apply it with: run.sh stop, run.sh configure, then start both services.'
        fi
        exit 0
        ;;
    *)
        echo "Usage: sh deploy/helios/run.sh {configure|organizations|manager|stop|update BUNDLE}" >&2
        exit 1
        ;;
esac
