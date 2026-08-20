# turnbase-game-host — CLAUDE.md

The Game Host. Runs game backends in an Espresso guest context and speaks gRPC to the Platform
Monolith. Docker image `turnbase/game-host`.

**This file is additive** — the corpus, registry, conventions and session workflow live in the parent
repository:

| | |
|---|---|
| Registry | `../docs/implementation/registry.md` — `IMP-M0-04`, `IMP-M3-02…04`, `IMP-M3-09` |
| Conventions | `../docs/implementation/conventions.md` — **normative** |
| Design | `../docs/specs/game-host/game-host-internals.md`, `../docs/decisions/0005-game-sandbox-espresso-spike.md` |

## The Java version thing, stated once

**Host JVM is Java 21. Guest is Espresso running Java 21.** The base image is **GraalVM CE for
JDK 21** (`21.0.2-graalce`).

There is **no GraalVM CE build for JDK 23.** The "CE 23" label that appears in older notes was the
deprecated GraalVM *product*-version scheme, not a JDK version. If you find yourself looking for a
CE 23 image, that is the trail you are on — stop.

**Espresso is not a JDK-level install.** `gu` was removed in GraalVM for JDK 21+, so Espresso arrives
as **Maven artifacts in this repo's pom**, not as something provisioned into the image.

## Package layout

Root package `io.turnbase.gamehost`. The build floor of `conventions.md` §6 applies — wrapper,
Spotless, JaCoCo, exact dependency versions.

**Spring Modulith does not apply here.** ADR-0014 is about the monolith's twelve features; the Game
Host is one bounded thing.

## The trust boundary is the whole point of this service

- **The platform cannot read game state, and neither can this service interpret it.** State crosses
  the boundary as an opaque payload. Anything that looks like inspecting it is a defect.
- **`espresso-spike-results.md` is normative, not history.** It disproved the assumption that the
  guest context blocks filesystem and network calls. What actually enforces isolation is the
  **container**, not the context — and Docker Compose enforces neither, so "it ran locally" is not
  "it is allowed".
- Every effect address is validated against the producing session's membership at fan-out.

## Before you commit

```bash
./mvnw spotless:apply
./mvnw verify
./mvnw verify -Pintegration
```

**Error Prone and NullAway fail the compile** ([ADR-0015](../docs/decisions/0015-static-analysis-error-prone-nullaway.md));
every package is `@NullMarked`. The generated gRPC stubs from `turnbase-common` are excluded from
NullAway's scope — if you see it complaining about generated code, the exclusion is wrong, not the
code.

`git config core.hooksPath .githooks` once, to get the `pre-push` fast-fail.

`turnbase-common` and `turnbase-sdk` are pinned by exact version — never `-SNAPSHOT` in a committed
pom (`conventions.md` §5).

## If a specification is wrong

**Stop, and fix the document in the parent repository first.** Where
`orchestration-gamehost-protocol.md` disagrees with `gamehost.proto`, the proto wins for wire shape —
but raise the disagreement rather than silently following one.
