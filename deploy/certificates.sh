#!/bin/sh
set -eu
for service in organizations manager; do
  directory="/certs/$service"
  if [ ! -f "$directory/server.p12" ]; then
    dns=organizations-service
    if [ "$service" = manager ]; then dns=orgmanager-service; fi
    keytool -genkeypair -alias server -keyalg RSA -keysize 3072 -validity 3650 \
      -dname "CN=$dns,OU=SOA Lab 2,O=Local Development" \
      -ext "SAN=dns:localhost,ip:127.0.0.1,dns:$dns" \
      -storetype PKCS12 -keystore "$directory/server.p12" \
      -storepass localchangeit -keypass localchangeit -noprompt
  fi
  keytool -exportcert -rfc -alias server -keystore "$directory/server.p12" \
    -storepass localchangeit -file "$directory/server.crt"
done
if [ ! -f /certs/manager/upstream-trust.p12 ]; then
  keytool -importcert -noprompt -alias organizations -storetype PKCS12 \
    -keystore /certs/manager/upstream-trust.p12 -storepass localchangeit \
    -file /certs/organizations/server.crt
fi
chmod 644 /certs/organizations/* /certs/manager/*
