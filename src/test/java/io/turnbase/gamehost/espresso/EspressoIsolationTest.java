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
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

/**
 * game-host-internals.md §4's isolation table, the Espresso-owned rows: each is a spike-verified
 * mechanism, exercised here rather than assumed. FR-CTX-004/005/007 are container-owned (§4.1) — no
 * deployment-manifest item exists yet to provision a container in this test, so they are out of
 * scope here (tracked in the registry's Closed-section scope note, not silently dropped).
 */
class EspressoIsolationTest {

  private static final String SDK_JAR_PATH = "/opt/turnbase/guest-bridge.jar";
  private static final String FIXTURE_FQCN =
      "io.turnbase.gamehost.espresso.fixture.EspressoFixtures";

  private static EspressoContextFactory factory() {
    return new EspressoContextFactory(System.getProperty("java.home"), SDK_JAR_PATH);
  }

  /** FR-CTX-001/003: one Context per key, each with an independently isolated heap/statics. */
  @Test
  void separateContextsHaveIndependentStatics() throws URISyntaxException {
    var classpath = EspressoContextLifecycleTest.fixtureClasspathDir();
    try (Context first = factory().create(classpath);
        Context second = factory().create(classpath)) {
      Value firstFixture = first.getBindings("java").getMember(FIXTURE_FQCN);
      Value secondFixture = second.getBindings("java").getMember(FIXTURE_FQCN);

      firstFixture.invokeMember("incrementAndGet");
      firstFixture.invokeMember("incrementAndGet");
      int firstCount = firstFixture.invokeMember("incrementAndGet").asInt();
      int secondCount = secondFixture.invokeMember("incrementAndGet").asInt();

      assertThat(firstCount).isEqualTo(3);
      assertThat(secondCount).isEqualTo(1);
    }
  }

  /** FR-CTX-006: guest {@code System.exit} disposes only the guest context, not the host JVM. */
  @Test
  void guestSystemExitDoesNotEscapeToTheHost() throws URISyntaxException {
    var classpath = EspressoContextLifecycleTest.fixtureClasspathDir();
    Context context = factory().create(classpath);
    Value fixture = context.getBindings("java").getMember(FIXTURE_FQCN);

    // The guest exit unwinds the calling thread immediately, but the exit status is only
    // reported on the PolyglotException that close() raises once the context has finished
    // tearing itself down.
    assertThatThrownBy(() -> fixture.invokeMember("exitWithCode", 42))
        .isInstanceOf(PolyglotException.class);

    assertThatThrownBy(context::close)
        .isInstanceOfSatisfying(
            PolyglotException.class,
            e -> {
              assertThat(e.isExit()).isTrue();
              assertThat(e.getExitStatus()).isEqualTo(42);
            });
    // Reaching this line at all is the proof: a real System.exit would have killed this JVM,
    // and the test process (and every assertion after it) would never have run.
    assertThat(true).isTrue();
  }

  /** FR-CTX-011: the guest runs as a real JDK-21 language context, not the Maven-bundled JDK 17. */
  @Test
  void guestRuntimeIsJdk21() throws URISyntaxException {
    var classpath = EspressoContextLifecycleTest.fixtureClasspathDir();
    try (Context context = factory().create(classpath)) {
      Value fixture = context.getBindings("java").getMember(FIXTURE_FQCN);
      assertThat(fixture.invokeMember("javaSpecVersion").asString()).isEqualTo("21");
    }
  }

  /** FR-CTX-008: a handler over its CPU/wall-clock budget is interrupted, then force-closed. */
  @Test
  void aRunawayHandlerIsInterruptedThenForceClosed() throws URISyntaxException {
    var classpath = EspressoContextLifecycleTest.fixtureClasspathDir();
    Context context = factory().create(classpath);
    try {
      Value fixture = context.getBindings("java").getMember(FIXTURE_FQCN);

      CompletableFuture<Void> handler =
          CompletableFuture.runAsync(() -> fixture.invokeMember("spinForever"));
      // Give the guest loop a moment to actually start spinning before interrupting it.
      awaitUninterruptibly(Duration.ofMillis(200));

      try {
        context.interrupt(Duration.ofSeconds(5));
      } catch (TimeoutException e) {
        // Escalation: interrupt() did not stop it in time, so the finally block's forced
        // close(true) below is what actually terminates the runaway handler.
      }

      assertThatThrownBy(() -> handler.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class);
    } finally {
      context.close(true);
    }
  }

  private static void awaitUninterruptibly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
