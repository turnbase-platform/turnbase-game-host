package io.turnbase.gamehost.warmup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.Test;

/**
 * Runs the synthetic session through a real Espresso context. As in {@code
 * GameBridgeClientEspressoTest}, the guest classpath is this JVM's test classpath, which already
 * carries the bridge classes (and so {@code WarmupGame}) the shaded jar would. Only one real
 * context is created: Espresso's native-library namespaces are finite per JVM, and a second one
 * here starves the other Espresso tests in the same surefire fork ({@code Cannot load library:
 * eden}).
 */
class GameHostWarmupTest {

  private static Context testContext() {
    return Context.newBuilder("java")
        .allowExperimentalOptions(true)
        .allowNativeAccess(true)
        .option("java.JavaHome", System.getProperty("java.home"))
        .option("java.ExitHost", "false")
        .option("java.Classpath", System.getProperty("java.class.path"))
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLoading(false)
        .allowCreateProcess(false)
        .allowEnvironmentAccess(EnvironmentAccess.NONE)
        .build();
  }

  @Test
  void runsAFullSyntheticSessionAndDisposesItsContext() {
    AtomicInteger contexts = new AtomicInteger();
    GameHostWarmup warmup =
        new GameHostWarmup(
            () -> {
              contexts.incrementAndGet();
              return testContext();
            });

    assertThat(warmup.run()).isPositive();
    assertThat(contexts).hasValue(1);
  }

  @Test
  void aDisabledLifecycleBootsNothingAndStillReportsRunning() {
    AtomicInteger contexts = new AtomicInteger();
    WarmupLifecycle lifecycle =
        new WarmupLifecycle(
            new GameHostWarmup(
                () -> {
                  contexts.incrementAndGet();
                  throw new IllegalStateException("must not boot");
                }),
            false);

    lifecycle.start();

    assertThat(contexts).hasValue(0);
    assertThat(lifecycle.isRunning()).isTrue();
  }

  @Test
  void lifecycleSwallowsAWarmupFailureSoReadinessIsNeverHeldHostage() {
    WarmupLifecycle lifecycle =
        new WarmupLifecycle(
            new GameHostWarmup(
                () -> {
                  throw new IllegalStateException("no guest JDK");
                }));

    assertThatCode(lifecycle::start).doesNotThrowAnyException();
    assertThat(lifecycle.isRunning()).isTrue();
  }
}
