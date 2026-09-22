package io.turnbase.gamehost.espresso;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;

/** A guest {@link Context} plus the bookkeeping {@link GameContextManager} needs to evict it. */
public final class ManagedContext implements AutoCloseable {

  private final ContextKey key;
  private final Context context;
  private final AtomicInteger activeSessions = new AtomicInteger();
  private volatile Instant lastUsed;

  ManagedContext(ContextKey key, Context context, Instant createdAt) {
    this.key = key;
    this.context = context;
    this.lastUsed = createdAt;
  }

  public ContextKey key() {
    return key;
  }

  public Context context() {
    return context;
  }

  void sessionStarted(Instant now) {
    activeSessions.incrementAndGet();
    lastUsed = now;
  }

  void sessionEnded(Instant now) {
    activeSessions.decrementAndGet();
    lastUsed = now;
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
