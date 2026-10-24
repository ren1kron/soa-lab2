FROM gradle:8.14.3-jdk17 AS build
WORKDIR /workspace
COPY openapi.yaml /workspace/openapi.yaml
COPY organizations-service /workspace/organizations-service
COPY deploy/HttpsHealthCheck.java /workspace/healthcheck-src/HttpsHealthCheck.java
WORKDIR /workspace/organizations-service
RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=locked gradle --no-daemon clean spotlessCheck test war
RUN javac -d /workspace/healthcheck /workspace/healthcheck-src/HttpsHealthCheck.java

FROM payara/server-full:6.2025.11-jdk17
COPY --from=build --chown=payara:payara /workspace/organizations-service/build/libs/organizations.war /opt/payara/deployments/organizations.war
COPY --chown=payara:payara organizations-service/deploy/preboot.asadmin /opt/payara/config/pre-boot-commands.asadmin
COPY --chown=payara:payara organizations-service/deploy/launch.sh /opt/payara/launch.sh
COPY --from=build --chown=payara:payara /workspace/healthcheck /opt/payara/healthcheck
ENTRYPOINT ["tini", "--", "/bin/bash", "/opt/payara/launch.sh"]
