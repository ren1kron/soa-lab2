#!/bin/bash
set -eu
keytool -importkeystore -noprompt -srckeystore /run/tls/server.p12 -srcstoretype PKCS12 \
  -srcstorepass localchangeit -srcalias server \
  -destkeystore "$PAYARA_DIR/glassfish/domains/$DOMAIN_NAME/config/keystore.p12" \
  -deststoretype PKCS12 -deststorepass changeit -destkeypass changeit -destalias server
exec /opt/payara/scripts/entrypoint.sh
