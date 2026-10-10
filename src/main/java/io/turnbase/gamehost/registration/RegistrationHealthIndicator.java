package io.turnbase.gamehost.registration;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code DOWN} until the first {@code Register} is acknowledged, so neither Compose nor Kubernetes
 * calls this host healthy while the orchestrator cannot yet route a {@code START_GAME} to it
 * (IMP-M10-08). Registration runs in the background (IMP-GATE-08), after Spring publishes
 * readiness, which left a window in which the host looked ready but was unknown to the registry.
 */
@Component("registration")
class RegistrationHealthIndicator implements HealthIndicator {

  private final RegistrationLifecycle lifecycle;

  RegistrationHealthIndicator(RegistrationLifecycle lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Override
  public Health health() {
    return lifecycle.hasRegistered()
        ? Health.up().build()
        : Health.down().withDetail("reason", "not registered with the orchestrator yet").build();
  }
}
