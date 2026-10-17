package io.turnbase.gamehost.espresso;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
 */
public final class BootWatchdog {

  private static final Logger log = LoggerFactory.getLogger(BootWatchdog.class);

  private static final BootWatchdog DISABLED = new BootWatchdog(Duration.ZERO, () -> {});

  private final Duration timeout;
  private final Runnable onWedge;
  private final @org.jspecify.annotations.Nullable ScheduledExecutorService scheduler;

  public BootWatchdog(Duration timeout, Runnable onWedge) {
    this.timeout = timeout;
    this.onWedge = onWedge;
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

  /** Runs {@code boot}; if it is still running after the timeout, fires {@code onWedge}. */
  public <T> T guard(String what, Supplier<T> boot) {
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
