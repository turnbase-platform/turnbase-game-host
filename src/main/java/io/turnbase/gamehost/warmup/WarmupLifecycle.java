package io.turnbase.gamehost.warmup;

import io.turnbase.gamehost.GameHostProperties;
import io.turnbase.gamehost.espresso.BootWatchdog;
import io.turnbase.gamehost.espresso.EspressoContextFactory;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs the warm-up synchronously during startup, before {@code RegistrationLifecycle} (default,
 * latest phase) registers this host and before Spring publishes readiness — so neither the
 * orchestrator nor Kubernetes routes the first real {@code START_GAME} here while Truffle is cold.
 * A failure is logged and startup continues: a host that cannot warm up is still a working host.
 *
 * <p>Off by default ({@code turnbase.gamehost.warmup-enabled}): the warm-up is a second Espresso
 * boot in the JVM, and that is the boot that wedges (game-host-internals.md §11, IMP-M16-03).
 */
@Component
class WarmupLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(WarmupLifecycle.class);

  private final GameHostWarmup warmup;
  private final boolean enabled;
  private volatile boolean running = false;

  @org.springframework.beans.factory.annotation.Autowired
  WarmupLifecycle(GameHostProperties properties, BootWatchdog watchdog) {
    this(
        new GameHostWarmup(
            new EspressoContextFactory(properties.guestJavaHome(), properties.sdkJarPath())
                ::createBridgeOnly,
            watchdog),
        properties.warmupEnabled());
  }

  WarmupLifecycle(GameHostWarmup warmup, boolean enabled) {
    this.warmup = warmup;
    this.enabled = enabled;
  }

  WarmupLifecycle(GameHostWarmup warmup) {
    this(warmup, true);
  }

  @Override
  public void start() {
    if (!enabled) {
      log.info(
          "Warm-up disabled (turnbase.gamehost.warmup-enabled=false); the first start boots cold");
      running = true;
      return;
    }
    try {
      Duration took = warmup.run();
      log.info("Warm-up session completed in {} ms", took.toMillis());
    } catch (RuntimeException e) {
      log.warn("Warm-up session failed; continuing cold: {}", e.getMessage(), e);
    }
    running = true;
  }

  @Override
  public void stop() {
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 1;
  }
}
