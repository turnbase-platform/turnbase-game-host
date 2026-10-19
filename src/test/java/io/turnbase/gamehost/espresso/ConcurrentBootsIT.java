package io.turnbase.gamehost.espresso;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * IMP-M16-04: four distinct contexts booted at the same moment, on real Espresso, through the
 * manager and a {@link BootWatchdog} that allows two boots at once. The boot timeout is measured,
 * not guessed: three times the slowest of two boots run side by side, which is what a capped boot
 * costs, so the four finish only because queued boots do not spend their budget waiting.
 *
 * <p>{@code sdkJarPath} is this JVM's own classpath, as in {@code SessionDispatcherIT}.
 */
class ConcurrentBootsIT {

  private static EspressoContextFactory factory() {
    return new EspressoContextFactory(
        System.getProperty("java.home"), System.getProperty("java.class.path"));
  }

  @Test
  void fourContextsBootAtOnceWithTwoPermitsAndNoWedge() throws Exception {
    java.nio.file.Path classpath = EspressoContextLifecycleTest.fixtureClasspathDir();

    // Calibrate: the first two boots in the JVM, side by side, unguarded.
    long pairMillis;
    try (GameContextManager calibration =
        new GameContextManager(factory(), Duration.ofMinutes(5), 0.95)) {
      pairMillis = bootAll(calibration, classpath, 0, 2);
    }

    AtomicInteger wedged = new AtomicInteger();
    BootWatchdog watchdog =
        new BootWatchdog(
            Duration.ofMillis(Math.max(3 * pairMillis, 20_000)), wedged::incrementAndGet, 2);
    try (GameContextManager manager =
        new GameContextManager(factory(), Duration.ofMinutes(5), 0.95, watchdog)) {
      bootAll(manager, classpath, 10, 4);

      assertThat(manager.liveContextKeys()).hasSize(4);
    }
    assertThat(wedged).hasValue(0);
  }

  private static long bootAll(
      GameContextManager manager, java.nio.file.Path classpath, int firstVersion, int count)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(count);
    try {
      long start = System.nanoTime();
      List<Future<Object>> boots =
          java.util.stream.IntStream.range(0, count)
              .<Future<Object>>mapToObj(
                  i ->
                      pool.submit(
                          () ->
                              manager
                                  .getOrCreate(
                                      new ContextKey("tic-tac-toe", "1." + (firstVersion + i)),
                                      classpath)
                                  .bridgeClient()))
              .toList();
      for (Future<Object> boot : boots) {
        assertThat(boot.get(120, TimeUnit.SECONDS)).isNotNull();
      }
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    } finally {
      pool.shutdownNow();
    }
  }
}
