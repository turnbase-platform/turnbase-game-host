package io.turnbase.gamehost.espresso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * IMP-M16-05: the isolation rows of game-host-internals.md §4 that Espresso owns still hold when
 * every context shares one {@link Engine}. A shared engine shares compiled code and caches, not the
 * guest heap; each case below runs two sibling contexts on one engine and shows that what happens
 * to one is not seen by the other.
 *
 * <p>What is deliberately not here: the container-owned rows (filesystem, network, memory), which
 * the engine does not change, and a statement limit, which is not usable with Espresso on CE
 * (ADR-0019). The engine does require one host-access configuration for all its contexts, which the
 * factory already gives every context.
 */
class SharedEngineIsolationTest {

  private static final String FIXTURE_FQCN =
      "io.turnbase.gamehost.espresso.fixture.EspressoFixtures";

  private static Engine engine;
  private static EspressoContextFactory factory;
  private static Context sibling;
  private static Value siblingFixture;

  /** One sibling context serves all three cases; each case adds only the context it breaks. */
  @BeforeAll
  static void shareOneEngine() throws URISyntaxException {
    engine = Engine.newBuilder().allowExperimentalOptions(true).build();
    factory =
        new EspressoContextFactory(
            System.getProperty("java.home"), "/opt/turnbase/guest-bridge.jar", engine);
    sibling = factory.create(EspressoContextLifecycleTest.fixtureClasspathDir());
    siblingFixture = sibling.getBindings("java").getMember(FIXTURE_FQCN);
  }

  @AfterAll
  static void closeEngine() {
    sibling.close(true);
    engine.close(true);
  }

  /** FR-CTX-001/003: statics are per context, engine or not. */
  @Test
  void siblingContextsOnOneEngineHaveIndependentStatics() throws URISyntaxException {
    int before = siblingFixture.invokeMember("incrementAndGet").asInt();
    try (Context other = factory.create(EspressoContextLifecycleTest.fixtureClasspathDir())) {
      Value otherFixture = other.getBindings("java").getMember(FIXTURE_FQCN);
      otherFixture.invokeMember("incrementAndGet");
      otherFixture.invokeMember("incrementAndGet");

      assertThat(otherFixture.invokeMember("incrementAndGet").asInt()).isEqualTo(3);
      assertThat(siblingFixture.invokeMember("incrementAndGet").asInt()).isEqualTo(before + 1);
    }
  }

  /** FR-CTX-006: a guest {@code System.exit} ends its own context and leaves the sibling usable. */
  @Test
  void guestExitInOneContextLeavesItsSiblingRunning() throws URISyntaxException {
    int before = siblingFixture.invokeMember("incrementAndGet").asInt();
    Context exiting = factory.create(EspressoContextLifecycleTest.fixtureClasspathDir());
    Value exitFixture = exiting.getBindings("java").getMember(FIXTURE_FQCN);
    assertThatThrownBy(() -> exitFixture.invokeMember("exitWithCode", 7))
        .isInstanceOf(PolyglotException.class);
    assertThatThrownBy(exiting::close)
        .isInstanceOfSatisfying(
            PolyglotException.class, e -> assertThat(e.getExitStatus()).isEqualTo(7));

    assertThat(siblingFixture.invokeMember("incrementAndGet").asInt()).isEqualTo(before + 1);
  }

  /**
   * FR-CTX-008: a runaway handler in one context is interrupted and force-closed while a sibling
   * context on the same engine keeps its state and keeps answering.
   */
  @Test
  void interruptingARunawayContextDoesNotStopItsSibling() throws Exception {
    int before = siblingFixture.invokeMember("incrementAndGet").asInt();
    Context runaway = factory.create(EspressoContextLifecycleTest.fixtureClasspathDir());
    Value spinFixture = runaway.getBindings("java").getMember(FIXTURE_FQCN);

    CompletableFuture<Void> handler =
        CompletableFuture.runAsync(() -> spinFixture.invokeMember("spinForever"));
    Thread.sleep(200);
    try {
      runaway.interrupt(Duration.ofSeconds(5));
    } catch (TimeoutException e) {
      // close(true) below is the escalation
    }
    runaway.close(true);

    assertThatThrownBy(() -> handler.get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class);
    assertThat(siblingFixture.invokeMember("incrementAndGet").asInt()).isEqualTo(before + 1);
  }
}
