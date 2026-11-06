package io.turnbase.gamehost.espresso;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many Espresso contexts this JVM may still create. A JVM can create only twelve in its
 * lifetime, closed or not: the thirteenth fails with {@code Cannot load library: eden}
 * (game-host-internals.md §18 {@code OQ-6}, closed by IMP-M20-18). Every creation takes one, also
 * the re-creation of an evicted context. Once the budget is spent, {@link #tryAcquire()} fails and
 * calls {@code onExhausted} once, which retires the host (drain, then a fresh JVM) instead of
 * leaving it registered and unable to start any game.
 */
public final class ContextBudget {

  private final int max;
  private final Runnable onExhausted;
  private final AtomicInteger used = new AtomicInteger();
  private final AtomicBoolean fired = new AtomicBoolean();

  public ContextBudget(int max, Runnable onExhausted) {
    this.max = max;
    this.onExhausted = onExhausted;
  }

  /** A budget that never runs out, for tests that do not exercise the limit. */
  public static ContextBudget unlimited() {
    return new ContextBudget(Integer.MAX_VALUE, () -> {});
  }

  /** Takes one creation; {@code false} (and the retirement started) when none is left. */
  public boolean tryAcquire() {
    if (used.getAndUpdate(n -> n < max ? n + 1 : n) < max) {
      return true;
    }
    if (fired.compareAndSet(false, true)) {
      onExhausted.run();
    }
    return false;
  }

  public int used() {
    return used.get();
  }
}
