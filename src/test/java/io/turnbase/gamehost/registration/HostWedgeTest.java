package io.turnbase.gamehost.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** A wedged host reports DOWN, leaves the registry, and exits without waiting for anything. */
class HostWedgeTest {

  @SuppressWarnings("unchecked")
  @Test
  void deregistersMarksUnhealthyAndHalts() throws Exception {
    RegistrationLifecycle lifecycle = mock(RegistrationLifecycle.class);
    ObjectProvider<RegistrationLifecycle> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(lifecycle);
    CountDownLatch halted = new CountDownLatch(1);
    HostWedge wedge = new HostWedge(provider, halted::countDown, () -> {});

    assertThat(wedge.isWedged()).isFalse();
    wedge.run();

    assertThat(wedge.isWedged()).isTrue();
    verify(lifecycle).stop();
    assertThat(halted.await(10, TimeUnit.SECONDS)).isTrue();
  }

  @SuppressWarnings("unchecked")
  @Test
  void stillHaltsWhenTheRegistryCallNeverReturns() throws Exception {
    RegistrationLifecycle lifecycle = mock(RegistrationLifecycle.class);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              Thread.sleep(60_000);
              return null;
            })
        .when(lifecycle)
        .stop();
    ObjectProvider<RegistrationLifecycle> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(lifecycle);
    CountDownLatch halted = new CountDownLatch(1);
    HostWedge wedge = new HostWedge(provider, halted::countDown, () -> {});

    Thread caller = new Thread(wedge);
    caller.setDaemon(true);
    caller.start();

    assertThat(halted.await(10, TimeUnit.SECONDS)).isTrue();
  }
}
