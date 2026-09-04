# turnbase-game-host

The Game Host — GraalVM CE for JDK 21, Espresso guest contexts, gRPC to the Platform Monolith. Ships
as the Docker image `turnbase/game-host`.

## Build

```bash
./mvnw verify                      # Spotless, Error Prone + NullAway, tests, coverage
./mvnw verify -Pintegration         # + Testcontainers-backed integration tests
./mvnw spotless:apply              # before opening a PR, not after CI rejects it
```

Toolchain is pinned in `.sdkmanrc` (`21.0.2-graalce`). Enable the local fast-fail once per clone:

```bash
git config core.hooksPath .githooks
```

**Bootstrap only:** `turnbase-common` has no tagged release yet, so `<turnbase-common.version>` pins
its current development version. Install it locally before building here:

```bash
cd ../turnbase-common && ./mvnw install -DskipTests
```

This step (and the equivalent one in CI) goes away the day `turnbase-common` is first tagged and the
version property here is bumped to the real release (`conventions.md` §5, IMP-GATE-07).

## Package layout

Root package `io.turnbase.gamehost`. Spring Modulith does not apply here — this is one bounded
service, not a feature-partitioned monolith (CLAUDE.md).

```
grpc/           the GameHostService server (port 9090)
registration/   the GameHostRegistryService client (Register/Heartbeat/ReportContexts/Deregister)
espresso/       guest context creation on GraalVM CE for JDK 21
```

## Run it

```bash
./mvnw spring-boot:run
```

`9090` serves gRPC (`GameHostService`); `8081` is the management port (health, readiness, liveness).
There is no REST API and no port `8080` — `server.port=-1` disables the main servlet container so
only the management port's own listener is open.

At startup the process attempts `Register` against `turnbase.gamehost.orchestration-address`
(default `platform:8080`) on a background thread, retrying for up to 60 seconds
(`local-environment.md` §3) without blocking startup. Until `IMP-M3-05` gives
`GameHostRegistryService` a real server implementation on the Platform Monolith, this will keep
retrying and logging failure — expected, not a regression (`IMP-GATE-08`).
