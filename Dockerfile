# Game Host — GraalVM CE for JDK 21, Espresso guest contexts. Unlike turnbase-platform's
# plain-JRE image, the host runtime itself must be GraalVM CE (Done-when: "a GraalVM CE for
# JDK 21 image starts with Espresso available").
#
# Single-stage: the jar is built by `./mvnw verify`/`package` before `docker build` runs (CI
# runs both in the same job), not inside the image — same reasoning as turnbase-platform's
# Dockerfile (conventions.md §5 bootstrap window, IMP-GATE-07).
FROM ghcr.io/graalvm/jdk-community:21.0.2

# Guest JDK 21 for Espresso: the Maven-bundled Espresso guest JDK is 17 and rejects Java-21
# (class major 65) guest code (espresso-spike-results.md). The host image already ships a
# real JDK 21 at $JAVA_HOME; exposing it at the path turnbase.gamehost.guest-java-home
# defaults to (local-environment.md §7.8) needs no second JDK download.
RUN ln -s "$JAVA_HOME" /opt/graalvm-guest-jdk21

WORKDIR /app

RUN useradd --system --create-home --shell /bin/false turnbase
USER turnbase

COPY target/turnbase-game-host-*.jar app.jar

# local-environment.md §1: 9090 gRPC, 8081 management. Bind address (loopback under compose,
# unset under Kubernetes) is set by MANAGEMENT_SERVER_ADDRESS, not baked into the image.
EXPOSE 9090 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
