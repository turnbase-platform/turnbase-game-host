package io.turnbase.gamehost.espresso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;

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

  /** IMP-M16-04: a slow boot for one key must not hold up the creation of any other key. */
  @Test
  void aSlowCreationForOneKeyDoesNotBlockOtherKeys() throws Exception {
    java.util.concurrent.CountDownLatch slowEntered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseSlow = new java.util.concurrent.CountDownLatch(1);
    Path slowPath = Path.of("slow");
    EspressoContextFactory slowFactory = org.mockito.Mockito.mock(EspressoContextFactory.class);
    org.mockito.Mockito.when(slowFactory.create(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              if (slowPath.equals(invocation.getArgument(0))) {
                slowEntered.countDown();
                releaseSlow.await();
              }
              return org.graalvm.polyglot.Context.create();
            });
    GameContextManager manager = new GameContextManager(slowFactory, Duration.ofMinutes(5), 0.8);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ManagedContext> slow =
          pool.submit(() -> manager.getOrCreate(new ContextKey("slow", "1"), slowPath));
      assertThat(slowEntered.await(5, TimeUnit.SECONDS)).isTrue();

      // Many keys, so that some share a hash bin with the slow one: that is what used to block.
      Future<?> others =
          pool.submit(
              () -> {
                for (int i = 0; i < 100; i++) {
                  manager.getOrCreate(new ContextKey("other-" + i, "1"), Path.of("fast"));
                }
              });
      others.get(10, TimeUnit.SECONDS);

      assertThat(slow.isDone()).isFalse();
      assertThat(manager.liveContextKeys()).hasSize(100);
      releaseSlow.countDown();
      assertThat(slow.get(10, TimeUnit.SECONDS)).isNotNull();
      assertThat(manager.liveContextKeys()).hasSize(101);
    } finally {
      releaseSlow.countDown();
      pool.shutdownNow();
      manager.close();
    }
  }

  /** A failed creation reaches every waiter and is not cached: the next call tries again. */
  @Test
  void aFailedCreationIsRethrownToWaitersAndRetriedLater() throws Exception {
    EspressoContextFactory failing = org.mockito.Mockito.mock(EspressoContextFactory.class);
    org.mockito.Mockito.when(failing.create(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("no jar"))
        .thenAnswer(invocation -> org.graalvm.polyglot.Context.create());
    GameContextManager manager = new GameContextManager(failing, Duration.ofMinutes(5), 0.8);
    ContextKey key = new ContextKey("g", "1");
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> manager.getOrCreate(key, Path.of("x")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("no jar");
      assertThat(manager.liveContextKeys()).isEmpty();

      assertThat(manager.getOrCreate(key, Path.of("x"))).isNotNull();
      assertThat(manager.liveContextKeys()).containsExactly(key);
    } finally {
      manager.close();
    }
  }

  /** IMP-M7-10: context create and release are INFO lines carrying the game and the reason. */
  @Test
  void contextCreateAndReleaseAreLoggedAtInfo() throws URISyntaxException {
    Logger logger = (Logger) LoggerFactory.getLogger(GameContextManager.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    AtomicReference<Instant> clock = new AtomicReference<>(Instant.EPOCH);
    GameContextManager manager =
        new GameContextManager(factory(), Duration.ofMinutes(5), 0.8, () -> 0.0, clock::get);
    try {
      manager.getOrCreate(new ContextKey("logged-game", "1.0.0"), fixtureClasspath());
      clock.set(Instant.EPOCH.plus(Duration.ofMinutes(6)));
      manager.evictIdle();
    } finally {
      manager.close();
      logger.detachAppender(appender);
    }
    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(m -> m.startsWith("Game context created") && m.contains("gameId=logged-game"))
        .anyMatch(
            m ->
                m.startsWith("Game context released")
                    && m.contains("gameId=logged-game")
                    && m.contains("reason=IDLE_TIMEOUT"));
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
