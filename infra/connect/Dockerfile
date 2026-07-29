# Kafka Connect (from the Kafka image) with the Apache Iceberg sink connector.
# ICEBERG_VERSION must match `iceberg` in gradle/libs.versions.toml.
ARG KAFKA_IMAGE=apache/kafka:4.3.2

FROM maven:3.9.12-eclipse-temurin-21 AS plugin
ARG ICEBERG_VERSION=1.12.0
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B -Diceberg.version=${ICEBERG_VERSION} dependency:copy-dependencies \
      -DincludeScope=runtime -DoutputDirectory=/plugins/iceberg-sink

FROM ${KAFKA_IMAGE}
COPY --from=plugin /plugins /opt/connect-plugins
# usage.raw protobuf -> Connect struct converter, staged by ./gradlew :libs:usage-connect:stageConnectPlugin
COPY plugins/usage-connect /opt/connect-plugins/usage-connect
COPY connect-distributed.properties /opt/kafka/config/connect-billing.properties
EXPOSE 8083
CMD ["/bin/sh", "-c", "sed -i \"s|^bootstrap.servers=.*|bootstrap.servers=${CONNECT_BOOTSTRAP_SERVERS:-kafka:9092}|\" /opt/kafka/config/connect-billing.properties && exec /opt/kafka/bin/connect-distributed.sh /opt/kafka/config/connect-billing.properties"]
