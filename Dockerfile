# Game Host — GraalVM CE for JDK 21, Espresso guest contexts. Unlike turnbase-platform's
# plain-JRE image, the host runtime itself must be GraalVM CE (Done-when: "a GraalVM CE for
# JDK 21 image starts with Espresso available").
#
# Single-stage: the jar is built by `./mvnw verify`/`package` before `docker build` runs (CI
# runs both in the same job), not inside the image — same reasoning as turnbase-platform's
# Dockerfile (conventions.md §5 bootstrap window, IMP-GATE-07).
FROM ghcr.io/graalvm/jdk-community:21.0.2

# observability.md §4: the OpenTelemetry Java agent, attached below via JAVA_TOOL_OPTIONS —
# same artifact and version as turnbase-platform's image.
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.1/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar

# Guest JDK 21 for Espresso: the Maven-bundled Espresso guest JDK is 17 and rejects Java-21
# (class major 65) guest code (espresso-spike-results.md). The host image already ships a
# real JDK 21 at $JAVA_HOME; exposing it at the path turnbase.gamehost.guest-java-home
# defaults to (local-environment.md §7.8) needs no second JDK download.
RUN ln -s "$JAVA_HOME" /opt/graalvm-guest-jdk21

WORKDIR /app

RUN useradd --system --create-home --shell /bin/false turnbase && \
    mkdir -p /opt/turnbase /var/lib/turnbase && \
    chown turnbase:turnbase /app /opt/turnbase /var/lib/turnbase
USER turnbase

# game-host-internals.md §3.6: `./mvnw package` now also produces a second, `guest-bridge`-
# classifier'd artifact (the shaded guest bridge jar) alongside the deployable app jar, so the
# glob that used to match this module's jar alone now matches two files. COPY can't express
# "this jar but not that one", so stage both candidates and sort each to where it belongs: the
# app jar as the image's entrypoint, the guest bridge jar at the path
# turnbase.gamehost.sdk-jar-path defaults to (application.yml).
COPY --chown=turnbase:turnbase target/turnbase-game-host-*.jar /tmp/jars/
RUN for f in /tmp/jars/*.jar; do \
      case "$f" in \
        *-guest-bridge.jar) cp "$f" /opt/turnbase/guest-bridge.jar ;; \
        *) cp "$f" app.jar ;; \
      esac; \
    done && \
    rm -rf /tmp/jars

# local-environment.md §1: 9090 gRPC, 8081 management. Bind address (loopback under compose,
# unset under Kubernetes) is set by MANAGEMENT_SERVER_ADDRESS, not baked into the image.
EXPOSE 9090 8081

# observability.md §4 — same reasoning as turnbase-platform's Dockerfile: only traces go
# through this agent, metrics and logs are Prometheus-scrape and Promtail's jobs instead.
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/opentelemetry-javaagent.jar" \
    OTEL_SERVICE_NAME=gamehost \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo:4317 \
    OTEL_EXPORTER_OTLP_PROTOCOL=grpc \
    OTEL_TRACES_SAMPLER=parentbased_traceidratio \
    OTEL_TRACES_SAMPLER_ARG=0.1 \
    OTEL_METRICS_EXPORTER=none \
    OTEL_LOGS_EXPORTER=none

ENTRYPOINT ["java", "-jar", "app.jar"]
