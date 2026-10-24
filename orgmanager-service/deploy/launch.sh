#!/bin/bash
set -eu
/opt/jboss/wildfly/bin/jboss-cli.sh --file=/opt/jboss/https.cli
exec /opt/jboss/wildfly/bin/standalone.sh -b 0.0.0.0 -bmanagement 127.0.0.1
