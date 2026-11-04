# turnbase-game-host — code map

The Game Host: a gRPC server that runs game backends (SDK JARs) in GraalVM Espresso contexts. Root
package `io.turnbase.gamehost`; wiring in `GameHostApplication` and `GameHostProperties`. Specified
in `docs/specs/game-host/` and `docs/specs/internal-grpc/`; checked by
`python3 scripts/check-corpus.py code-maps` from the umbrella repository.

## Packages

| Package | Responsibility |
|---|---|
| `io.turnbase.gamehost.grpc` | gRPC server lifecycle and the `GameHostServiceImpl` entry points; correlation-ID interceptor |
| `io.turnbase.gamehost.dispatch` | `SessionDispatcher`: create, process, resync, terminate; session records; envelope encoding |
| `io.turnbase.gamehost.espresso` | Espresso context creation, per-session context management, idle and memory eviction, boot watchdog |
| `io.turnbase.gamehost.bridge` | Host side of the guest boundary: the context objects handed to game code, JSON conversion |
| `io.turnbase.gamehost.shim` | `GameBridgeClient`, the guest-side shim loaded into the context |
| `io.turnbase.gamehost.artifact` | Game artifact store and the local artifact cache |
| `io.turnbase.gamehost.registration` | Registration with the platform, heartbeat loop, drain, wedge detection |
| `io.turnbase.gamehost.warmup` | Startup warm-up of the Espresso engine |

## Flows

### Sign-in, room create/join

Not involved: the Game Host sees no end-user traffic. Only the platform's correlation ID arrives, via `GrpcCorrelationServerInterceptor`.

### Game start (`START_GAME`)

1. `GameHostServiceImpl.createSession` (`rpc CreateSession`) → `SessionDispatcher.createSession`.
2. `GameArtifactResolver` finds the game version; `ArtifactCache.get` fetches the JAR from the store (`ArtifactStore`, `S3ArtifactStore`).
3. `GameContextManager.getOrCreate` builds a context (`EspressoContextFactory`, `GuestClasspathBuilder`, `BootWatchdog`).
4. `GameEntrypoint` calls the game's `init`; the result is recorded in `SessionRecordStore`.

### A move

1. `GameHostServiceImpl.processInput` (`rpc ProcessInput`) → `SessionDispatcher.processInput`.
2. `EnvelopeCodec` decodes; `GameEntrypoint` invokes the game with `ActionContextImpl`; `PlatformJson` converts across the boundary.
3. The reply carries the new state and effects; `SessionRecordCodec` persists the record. `resync` follows the same path with `ResyncContextImpl`.

### Game upload

Not involved at upload time; the Game Host first sees a bundle when `ArtifactCache.get` pulls it for a session.

### Game Host failure

1. `OrchestrationRegistrationClient.registerWithRetry` registers; `HostLivenessLoop.run` heartbeats and `HostLivenessLoop.beginDrain` drains.
2. A hung context is caught by `BootWatchdog`; `HostWedge` marks a wedged host and `RegistrationHealthIndicator` reports it.
3. `GameContextManager.evictIdle` / `evictUnderMemoryPressure` bound memory; `SessionDispatcher.terminateSession` ends sessions the platform terminates (`TerminatedSessions` remembers them).
