package io.turnbase.gamehost.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/** IMP-M10-08: healthy only once the orchestrator knows this host. */
class RegistrationHealthIndicatorTest {

  @Test
  void downUntilRegisteredThenUp() {
    RegistrationLifecycle lifecycle = mock(RegistrationLifecycle.class);
    var indicator = new RegistrationHealthIndicator(lifecycle);

    when(lifecycle.hasRegistered()).thenReturn(false);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

    when(lifecycle.hasRegistered()).thenReturn(true);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }
}
