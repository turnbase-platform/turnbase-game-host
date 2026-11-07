# turnbase-game-host

The Game Host — Spring Boot 4.1 (Jackson 3 host-side), GraalVM CE for JDK 21, Espresso guest contexts, gRPC to the Platform Monolith. Ships
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

## Isolation depends on the container

The Espresso context isolates game-version heaps, contains `System.exit` and stops runaway
handlers (`Context.interrupt`). It does **not** block filesystem or network access or cap memory
on GraalVM CE — the guest JDK's native `java.io`/`java.net` run straight against the host OS. Those
three guarantees (FR-CTX-004/005/007) come from the **container**: read-only rootfs, egress
`NetworkPolicy`, cgroup memory limit, and gVisor in production. They are not provisioned by the
Compose stack or the Helm chart yet, and the Game Host is safe only because it hosts trusted
official games. Full table: [`game-host-internals.md` §4.3](../docs/specs/game-host/game-host-internals.md);
evidence: [`espresso-spike-results.md`](../docs/reviews/2026-07-02-mvp-readiness/espresso-spike-results.md).

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

## Third-party licences

What this repository distributes is audited for its dependencies' licences (allowed families:
`docs/implementation/conventions.md` §6 *Third-party licences*). The report is generated, not committed:

```bash
./mvnw org.codehaus.mojo:license-maven-plugin:2.8.0:add-third-party -Dlicense.excludedScopes=test,provided
# -> target/generated-sources/license/THIRD-PARTY.txt
```

The Espresso / Polyglot artifacts report `GNU General Public License, version 2`; they are GPLv2 with the
Classpath Exception and are allowed only here, as runtime components of this image.
