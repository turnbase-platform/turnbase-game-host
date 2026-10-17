package io.turnbase.gamehost.espresso;

import io.turnbase.gamehost.shim.GameBridgeClient;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.jspecify.annotations.Nullable;

/** A guest {@link Context} plus the bookkeeping {@link GameContextManager} needs to evict it. */
public final class ManagedContext implements AutoCloseable {

  private final ContextKey key;
  private final Context context;
  private final BootWatchdog watchdog;
  private @Nullable GameBridgeClient bridgeClient;
  private final AtomicInteger activeSessions = new AtomicInteger();
  private volatile Instant lastUsed;

  ManagedContext(ContextKey key, Context context, Instant createdAt, BootWatchdog watchdog) {
    this.key = key;
    this.watchdog = watchdog;
    this.context = context;
    this.lastUsed = createdAt;
  }

  public ContextKey key() {
    return key;
  }

  public Context context() {
    return context;
  }

  /**
   * The single {@code GameEntrypoint} handle for this context (§3.3); drives every session on it.
   *
   * <p>Lazily built on first use rather than in the constructor: building it spawns the guest's
   * native VM access (game-host-internals.md §3.3), which contexts created only to be evicted or
   * coalesced (e.g. lifecycle-policy tests) never need to pay for.
   */
  public synchronized GameBridgeClient bridgeClient() {
    if (bridgeClient == null) {
      bridgeClient =
          watchdog.guard(
              "context " + key.gameId() + " " + key.version(), () -> new GameBridgeClient(context));
    }
    return bridgeClient;
  }

  void sessionStarted(Instant now) {
    activeSessions.incrementAndGet();
    lastUsed = now;
  }

  void sessionEnded(Instant now) {
    activeSessions.decrementAndGet();
    lastUsed = now;
  }

  int activeSessionCount() {
    return activeSessions.get();
  }

  boolean hasActiveSessions() {
    return activeSessions.get() > 0;
  }

  Instant lastUsed() {
    return lastUsed;
  }

  /** Guest {@code System.exit} disposes only this context (FR-CTX-006) — dispose is always safe. */
  @Override
  public void close() {
    context.close(true);
  }
}
