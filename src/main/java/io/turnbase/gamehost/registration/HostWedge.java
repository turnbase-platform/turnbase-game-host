package io.turnbase.gamehost.registration;

import io.turnbase.gamehost.GameHostProperties;
import io.turnbase.gamehost.espresso.BootWatchdog;
import io.turnbase.gamehost.espresso.ContextBudget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * What the host does once {@link BootWatchdog} says a guest boot is wedged: report {@code DOWN},
 * leave the registry so the orchestrator stops routing here, and get a fresh process started. The
 * JVM cannot be recovered — the wedged thread holds the C library's loader lock — and it ignores
 * {@code SIGTERM}, so the exit is {@link Runtime#halt(int)}. That call can block on the same lock,
 * so the host also tells {@code docker/supervise.sh}: it publishes {@link LivenessState#BROKEN}
 * (the liveness probe turns 503) and writes the marker file the supervisor watches, and the
 * supervisor SIGKILLs the JVM and starts the next one (IMP-M16-02). No external process is started
 * from here: the image has no {@code kill} binary.
 */
@Component
class HostWedge implements Runnable {

  static final int EXIT_CODE = 70;

  private static final Logger log = LoggerFactory.getLogger(HostWedge.class);
  private static final Duration DEREGISTER_GRACE = Duration.ofSeconds(4);

  private final ObjectProvider<RegistrationLifecycle> registration;
  private final ApplicationEventPublisher events;
  private final GameHostProperties properties;
  private final Runnable halt;
  private volatile boolean wedged = false;
  private volatile boolean retiring = false;

  @Autowired
  HostWedge(
      ObjectProvider<RegistrationLifecycle> registration,
      ApplicationEventPublisher events,
      GameHostProperties properties) {
    this(registration, events, properties, () -> Runtime.getRuntime().halt(EXIT_CODE));
  }

  HostWedge(
      ObjectProvider<RegistrationLifecycle> registration,
      ApplicationEventPublisher events,
      GameHostProperties properties,
      Runnable halt) {
    this.registration = registration;
    this.events = events;
    this.properties = properties;
    this.halt = halt;
  }

  boolean isWedged() {
    return wedged;
  }

  boolean isRetiring() {
    return retiring;
  }

  @Override
  public void run() {
    if (wedged) {
      return;
    }
    wedged = true;
    exitAndDeregister(true);
  }

  /**
   * The JVM has created all the Espresso contexts it ever can (OQ-6, {@link ContextBudget}). Unlike
   * a wedge the JVM is healthy, so first drain: announce draining, keep serving the sessions it has
   * for {@code drain-period}, deregister; only then exit as for a wedge, and the supervisor starts
   * a fresh JVM with a fresh budget. Whatever session remains is reassigned (§9.3).
   */
  void retire() {
    if (retiring) {
      return;
    }
    retiring = true;
    log.warn("All guest contexts of this JVM are spent; draining, then restarting");
    Thread drain =
        new Thread(
            () -> {
              RegistrationLifecycle lifecycle = registration.getIfAvailable();
              if (lifecycle != null) {
                lifecycle.stop();
              }
              if (!wedged) {
                wedged = true;
                exitAndDeregister(false);
              }
            },
            "host-retire");
    drain.setDaemon(true);
    drain.start();
  }

  private void exitAndDeregister(boolean deregister) {
    writeMarker();
    events.publishEvent(new AvailabilityChangeEvent<>(this, LivenessState.BROKEN));
    startExitTimer();
    RegistrationLifecycle lifecycle = deregister ? registration.getIfAvailable() : null;
    if (lifecycle != null) {
      lifecycle.stop();
    }
  }

  /** The supervisor's cue (mode B in {@code docker/supervise.sh}); best effort. */
  private void writeMarker() {
    try {
      Files.writeString(Path.of(properties.wedgeMarker()), "wedged\n");
    } catch (IOException | RuntimeException e) {
      log.error("Could not write the wedge marker; relying on the liveness probe", e);
    }
  }

  /** Started before the deregister so a hung call cannot keep a dead host in the registry. */
  private void startExitTimer() {
    Thread exit =
        new Thread(
            () -> {
              sleep(DEREGISTER_GRACE);
              log.error("Exiting with code {} after a wedged guest boot", EXIT_CODE);
              halt.run();
            },
            "wedge-exit");
    exit.setDaemon(true);
    exit.start();
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @org.springframework.context.annotation.Configuration
  static class Config {

    @Bean
    BootWatchdog bootWatchdog(GameHostProperties properties, HostWedge wedge) {
      return new BootWatchdog(properties.bootTimeout(), wedge, properties.maxConcurrentBoots());
    }

    /** The warm-up context, when enabled, is one of the JVM's creations too. */
    @Bean
    ContextBudget contextBudget(GameHostProperties properties, HostWedge wedge) {
      int max = properties.maxContextCreations() - (properties.warmupEnabled() ? 1 : 0);
      return new ContextBudget(max, wedge::retire);
    }
  }
}
