package io.turnbase.gamehost.espresso;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounds the one step that can hang for good: the first guest call, where Espresso boots. On a
 * glibc 2.34 base image that boot sometimes deadlocks — Truffle's {@code nfi-gc} thread {@code
 * dlclose}s a library handle while the boot thread {@code dlsym}s, and the loader lock is never
 * released (about one cold boot in ten; see ADR-0018). The JVM stays alive, healthy and registered,
 * so nothing outside notices and the lease keeps routing {@code CreateSession} into a host that
 * will never answer.
 *
 * <p>The watchdog does not try to unwedge the boot: a thread in that state cannot be interrupted.
 * It runs {@code onWedge} once the boot has taken longer than {@code timeout}, from a thread of its
 * own, and the host is expected to take itself out of service and exit so its supervisor starts a
 * fresh process.
 *
 * <p>It also caps how many boots run at once (IMP-M16-04): the boots are CPU-bound and slow each
 * other down, so the cap keeps each one far from the timeout. A boot waits for a permit first, in
 * arrival order, and its timer starts only when it has the permit — time spent queued is not time
 * spent wedged.
 */
public final class BootWatchdog {

  private static final Logger log = LoggerFactory.getLogger(BootWatchdog.class);

  private static final BootWatchdog DISABLED = new BootWatchdog(Duration.ZERO, () -> {});

  private final Duration timeout;
  private final Runnable onWedge;
  private final Semaphore permits;
  private final @org.jspecify.annotations.Nullable ScheduledExecutorService scheduler;

  /** No cap on concurrent boots. */
  public BootWatchdog(Duration timeout, Runnable onWedge) {
    this(timeout, onWedge, Integer.MAX_VALUE);
  }

  /**
   * @param maxConcurrentBoots how many boots may run at once; the rest queue, fairly
   */
  public BootWatchdog(Duration timeout, Runnable onWedge, int maxConcurrentBoots) {
    if (maxConcurrentBoots < 1) {
      throw new IllegalArgumentException(
          "max-concurrent-boots must be at least 1, got " + maxConcurrentBoots);
    }
    this.timeout = timeout;
    this.onWedge = onWedge;
    this.permits = new Semaphore(maxConcurrentBoots, true);
    this.scheduler =
        timeout.isZero()
            ? null
            : Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                  Thread thread = new Thread(runnable, "boot-watchdog");
                  thread.setDaemon(true);
                  return thread;
                });
  }

  /** A watchdog that guards nothing, for contexts that do not need one. */
  public static BootWatchdog disabled() {
    return DISABLED;
  }

  /**
   * Waits for a permit, then runs {@code boot}; if it is still running {@code timeout} after it got
   * the permit, fires {@code onWedge}. The wait is bounded by the caller, not here: an interrupt
   * ends it with an {@link IllegalStateException} and the thread's interrupt flag set.
   */
  public <T> T guard(String what, Supplier<T> boot) {
    try {
      permits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting to boot " + what, e);
    }
    try {
      return guardAlarm(what, boot);
    } finally {
      permits.release();
    }
  }

  private <T> T guardAlarm(String what, Supplier<T> boot) {
    if (scheduler == null) {
      return boot.get();
    }
    long start = System.nanoTime();
    ScheduledFuture<?> alarm =
        scheduler.schedule(
            () -> {
              log.error(
                  "Guest boot wedged: {} still running after {} ms; taking this host out of service",
                  what,
                  TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
              onWedge.run();
            },
            timeout.toMillis(),
            TimeUnit.MILLISECONDS);
    try {
      return boot.get();
    } finally {
      alarm.cancel(false);
    }
  }
}
