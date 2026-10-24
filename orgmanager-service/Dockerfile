FROM gradle:8.14.3-jdk17 AS build
WORKDIR /workspace
COPY openapi.yaml openapi-manager.yaml /workspace/
COPY orgmanager-service /workspace/orgmanager-service
COPY deploy/HttpsHealthCheck.java /workspace/healthcheck-src/HttpsHealthCheck.java
WORKDIR /workspace/orgmanager-service
RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=locked gradle --no-daemon clean spotlessCheck test war
RUN javac -d /workspace/healthcheck /workspace/healthcheck-src/HttpsHealthCheck.java

FROM quay.io/wildfly/wildfly:35.0.1.Final-jdk17
COPY --from=build --chown=jboss:root /workspace/orgmanager-service/build/libs/orgmanager.war /opt/jboss/wildfly/standalone/deployments/ROOT.war
COPY --chown=jboss:root orgmanager-service/deploy/https.cli /opt/jboss/https.cli
COPY --chown=jboss:root orgmanager-service/deploy/launch.sh /opt/jboss/launch.sh
COPY --from=build --chown=jboss:root /workspace/healthcheck /opt/jboss/healthcheck
ENTRYPOINT ["/bin/bash", "/opt/jboss/launch.sh"]
