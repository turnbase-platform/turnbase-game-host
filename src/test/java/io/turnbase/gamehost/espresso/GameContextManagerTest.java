package io.turnbase.gamehost.espresso;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * §6's context/session model: creation-on-demand with coalescing (FR-CTX-002), idle eviction
 * (FR-CTX-009), memory-pressure LRU eviction (FR-CTX-010), and the invariant that a context with an
 * active session is never evicted by either policy (§6.3).
 */
class GameContextManagerTest {

  private static EspressoContextFactory factory() {
    return new EspressoContextFactory(
        System.getProperty("java.home"), "/opt/turnbase/guest-bridge.jar");
  }

  private static Path fixtureClasspath() throws URISyntaxException {
    return EspressoContextLifecycleTest.fixtureClasspathDir();
  }

  @Test
  void concurrentRequestsForTheSameColdKeyCoalesceToOneContext() throws Exception {
    GameContextManager manager = new GameContextManager(factory(), Duration.ofMinutes(5), 0.8);
    ContextKey key = new ContextKey("tic-tac-toe", "1.0.0");
    Path classpath = fixtureClasspath();

    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      var futures =
          IntStream.range(0, 8)
              .<Future<ManagedContext>>mapToObj(
                  i -> pool.submit(() -> manager.getOrCreate(key, classpath)))
              .toList();
      Set<ManagedContext> results = new HashSet<>();
      for (Future<ManagedContext> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      assertThat(results).hasSize(1);
      assertThat(manager.liveContextKeys()).containsExactly(key);
    } finally {
      pool.shutdownNow();
      manager.close();
    }
  }

  @Test
  void idleContextsAreEvictedButActiveSessionsProtectThem() throws URISyntaxException {
    AtomicReference<Instant> clock = new AtomicReference<>(Instant.EPOCH);
    GameContextManager manager =
        new GameContextManager(factory(), Duration.ofMinutes(5), 0.8, () -> 0.0, clock::get);
    Path classpath = fixtureClasspath();
    ContextKey idleKey = new ContextKey("idle-game", "1.0.0");
    ContextKey activeKey = new ContextKey("active-game", "1.0.0");

    try {
      manager.getOrCreate(idleKey, classpath);
      manager.getOrCreate(activeKey, classpath);
      manager.sessionStarted(activeKey);

      clock.set(Instant.EPOCH.plus(Duration.ofMinutes(10)));
      manager.evictIdle();

      assertThat(manager.liveContextKeys()).containsExactly(activeKey);
    } finally {
      manager.close();
    }
  }

  @Test
  void memoryPressureEvictsOnlyTheLeastRecentlyUsedIdleContext() throws URISyntaxException {
    AtomicReference<Instant> clock = new AtomicReference<>(Instant.EPOCH);
    AtomicReference<Double> memoryUsage = new AtomicReference<>(0.0);
    GameContextManager manager =
        new GameContextManager(factory(), Duration.ofMinutes(5), 0.8, memoryUsage::get, clock::get);
    Path classpath = fixtureClasspath();
    ContextKey older = new ContextKey("older-game", "1.0.0");
    ContextKey newer = new ContextKey("newer-game", "1.0.0");

    try {
      manager.getOrCreate(older, classpath);
      clock.set(Instant.EPOCH.plusSeconds(1));
      manager.getOrCreate(newer, classpath);

      memoryUsage.set(0.95);
      manager.evictUnderMemoryPressure();

      assertThat(manager.liveContextKeys()).containsExactly(newer);
    } finally {
      manager.close();
    }
  }

  @Test
  void memoryPressureNeverEvictsAContextWithAnActiveSession() throws URISyntaxException {
    AtomicReference<Instant> clock = new AtomicReference<>(Instant.EPOCH);
    AtomicReference<Double> memoryUsage = new AtomicReference<>(0.0);
    GameContextManager manager =
        new GameContextManager(factory(), Duration.ofMinutes(5), 0.8, memoryUsage::get, clock::get);
    Path classpath = fixtureClasspath();
    ContextKey onlyKey = new ContextKey("busy-game", "1.0.0");

    try {
      manager.getOrCreate(onlyKey, classpath);
      manager.sessionStarted(onlyKey);

      memoryUsage.set(0.99);
      manager.evictUnderMemoryPressure();

      assertThat(manager.liveContextKeys()).containsExactly(onlyKey);
    } finally {
      manager.close();
    }
  }
}
