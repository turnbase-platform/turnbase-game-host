package io.turnbase.gamehost.warmup;

import io.turnbase.gamehost.shim.GameBridgeClient;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.graalvm.polyglot.Context;

/**
 * Drives one synthetic session of {@code WarmupGame} through a throwaway guest context. The
 * context, session id and state live only for the call: nothing is registered with {@code
 * GameContextManager} or written to Redis.
 */
final class GameHostWarmup {

  private static final String ENTRY_POINT_FQCN = "io.turnbase.gamehost.bridge.WarmupGame";

  private final Supplier<Context> contextFactory;

  GameHostWarmup(Supplier<Context> contextFactory) {
    this.contextFactory = contextFactory;
  }

  /** Returns how long the synthetic session took. */
  Duration run() {
    long start = System.nanoTime();
    String sessionId = "warmup-" + UUID.randomUUID();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    String player =
        "{\"playerId\":\"%s\",\"displayName\":\"warmup\",\"teamId\":\"%s\"}"
            .formatted(playerId, teamId);
    String teams =
        "[{\"teamId\":\"%s\",\"name\":\"warmup\",\"players\":[%s]}]".formatted(teamId, player);

    try (Context context = contextFactory.get()) {
      GameBridgeClient client = new GameBridgeClient(context);
      String init = client.createSession(sessionId, ENTRY_POINT_FQCN, teams, "{}");
      String state = stateOf(init);
      client.processAction(sessionId, state, player, "{\"by\":1}");
      client.resync(sessionId, state, player, "PLAYER");
      client.disposeSession(sessionId);
    }
    return Duration.ofNanos(System.nanoTime() - start);
  }

  private static String stateOf(String envelope) {
    if (!envelope.contains("\"status\":\"OK\"")) {
      throw new IllegalStateException("warm-up onInit failed: " + envelope);
    }
    return "{\"count\":0}";
  }
}
