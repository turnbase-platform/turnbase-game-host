package io.turnbase.gamehost.espresso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A JVM can create only twelve Espresso contexts in its lifetime ({@code OQ-6}). The budget counts
 * every creation, refuses the one past the limit instead of letting Espresso fail, and starts the
 * host's retirement exactly once.
 */
class ContextBudgetTest {

  @Test
  void grantsExactlyTheBudgetThenRefusesAndRetiresOnce() {
    AtomicInteger retired = new AtomicInteger();
    ContextBudget budget = new ContextBudget(3, retired::incrementAndGet);

    assertThat(budget.tryAcquire()).isTrue();
    assertThat(budget.tryAcquire()).isTrue();
    assertThat(budget.tryAcquire()).isTrue();
    assertThat(retired).hasValue(0);

    assertThat(budget.tryAcquire()).isFalse();
    assertThat(budget.tryAcquire()).isFalse();
    assertThat(retired).hasValue(1);
    assertThat(budget.used()).isEqualTo(3);
  }

  @Test
  void aCreationPastTheBudgetIsRefusedBeforeEspressoIsAskedAndRecreationCounts() throws Exception {
    AtomicInteger retired = new AtomicInteger();
    EspressoContextFactory factory =
        new EspressoContextFactory(
            System.getProperty("java.home"), "/opt/turnbase/guest-bridge.jar");
    GameContextManager manager =
        new GameContextManager(
            factory,
            Duration.ZERO,
            0.8,
            BootWatchdog.disabled(),
            new ContextBudget(1, retired::incrementAndGet));
    ContextKey key = new ContextKey("tic-tac-toe", "1.0.0");
    Path classpath = fixtureClasspath();
    try {
      manager.getOrCreate(key, classpath);
      assertThat(manager.getOrCreate(key, classpath)).isNotNull(); // live: no new creation
      assertThat(retired).hasValue(0);

      manager.evictIdle(); // the context is closed; the next request must create again
      assertThatThrownBy(() -> manager.getOrCreate(key, classpath))
          .isInstanceOf(ContextBudgetExhaustedException.class);
      assertThat(retired).hasValue(1);
      assertThat(manager.liveContextKeys()).isEmpty();
    } finally {
      manager.close();
    }
  }

  private static Path fixtureClasspath() throws URISyntaxException {
    return EspressoContextLifecycleTest.fixtureClasspathDir();
  }
}
