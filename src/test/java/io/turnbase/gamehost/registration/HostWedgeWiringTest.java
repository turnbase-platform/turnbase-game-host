package io.turnbase.gamehost.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.turnbase.gamehost.GameHostProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * {@link HostWedge} has a second, test-only constructor. Spring must still pick the production one
 * — the first image built from this class failed to start with "No default constructor found".
 */
class HostWedgeWiringTest {

  @Test
  void springInstantiatesTheProductionConstructor() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      GameHostProperties properties = mock(GameHostProperties.class);
      when(properties.bootTimeout()).thenReturn(Duration.ZERO);
      context.registerBean(GameHostProperties.class, () -> properties);
      context.registerBean(HostWedge.class);
      context.refresh();
      assertThat(context.getBean(HostWedge.class).isWedged()).isFalse();
    }
  }
}
