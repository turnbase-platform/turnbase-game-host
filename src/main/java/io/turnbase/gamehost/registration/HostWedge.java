package io.turnbase.gamehost.registration;

import io.turnbase.gamehost.GameHostProperties;
import io.turnbase.gamehost.espresso.BootWatchdog;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * What the host does once {@link BootWatchdog} says a guest boot is wedged: report {@code DOWN},
 * leave the registry so the orchestrator stops routing here, and exit so the supervisor (Compose
 * {@code restart}, a Kubernetes container restart) starts a fresh process. The JVM cannot be
 * recovered — the wedged thread holds the C library's loader lock — and it ignores {@code SIGTERM},
 * so the exit is {@link Runtime#halt(int)}, with an external {@code SIGKILL} behind it for the case
 * where even that blocks on the same lock.
 */
@Component
class HostWedge implements Runnable {

  static final int EXIT_CODE = 70;

  private static final Logger log = LoggerFactory.getLogger(HostWedge.class);
  private static final Duration DEREGISTER_GRACE = Duration.ofSeconds(4);
  private static final Duration KILL_GRACE = Duration.ofSeconds(3);

  private final ObjectProvider<RegistrationLifecycle> registration;
  private final Runnable halt;
  private final Runnable kill;
  private volatile boolean wedged = false;

  @Autowired
  HostWedge(ObjectProvider<RegistrationLifecycle> registration) {
    this(registration, () -> Runtime.getRuntime().halt(EXIT_CODE), HostWedge::sigkillSelf);
  }

  HostWedge(ObjectProvider<RegistrationLifecycle> registration, Runnable halt, Runnable kill) {
    this.registration = registration;
    this.halt = halt;
    this.kill = kill;
  }

  boolean isWedged() {
    return wedged;
  }

  @Override
  public void run() {
    if (wedged) {
      return;
    }
    wedged = true;
    startExitTimers();
    RegistrationLifecycle lifecycle = registration.getIfAvailable();
    if (lifecycle != null) {
      lifecycle.stop();
    }
  }

  /** Started before the deregister so a hung call cannot keep a dead host in the registry. */
  private void startExitTimers() {
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
    Thread killer =
        new Thread(
            () -> {
              sleep(DEREGISTER_GRACE.plus(KILL_GRACE));
              kill.run();
            },
            "wedge-kill");
    killer.setDaemon(true);
    killer.start();
  }

  private static void sigkillSelf() {
    try {
      new ProcessBuilder("kill", "-9", Long.toString(ProcessHandle.current().pid()))
          .inheritIO()
          .start();
    } catch (java.io.IOException | RuntimeException e) {
      log.error("SIGKILL fallback failed", e);
    }
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
      return new BootWatchdog(properties.bootTimeout(), wedge);
    }
  }
}
