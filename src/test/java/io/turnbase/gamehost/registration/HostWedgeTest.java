package io.turnbase.gamehost.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.turnbase.gamehost.GameHostProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A wedged host reports DOWN and BROKEN, leaves the registry, tells the supervisor and exits
 * without waiting for anything — and starts no process of its own (IMP-M16-02).
 */
class HostWedgeTest {

  @TempDir Path dir;

  private final List<Object> published = new ArrayList<>();
  private final ApplicationEventPublisher events = published::add;

  @SuppressWarnings("unchecked")
  private static ObjectProvider<RegistrationLifecycle> providerOf(RegistrationLifecycle lifecycle) {
    ObjectProvider<RegistrationLifecycle> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(lifecycle);
    return provider;
  }

  private GameHostProperties properties(Path marker) {
    GameHostProperties properties = mock(GameHostProperties.class);
    when(properties.wedgeMarker()).thenReturn(marker.toString());
    return properties;
  }

  @Test
  void deregistersMarksUnhealthyAndHalts() throws Exception {
    RegistrationLifecycle lifecycle = mock(RegistrationLifecycle.class);
    CountDownLatch halted = new CountDownLatch(1);
    HostWedge wedge =
        new HostWedge(
            providerOf(lifecycle), events, properties(dir.resolve("m")), halted::countDown);

    assertThat(wedge.isWedged()).isFalse();
    wedge.run();

    assertThat(wedge.isWedged()).isTrue();
    verify(lifecycle).stop();
    assertThat(halted.await(10, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void publishesBrokenWritesTheMarkerAndStartsNoProcess() throws Exception {
    Path marker = dir.resolve("turnbase-wedged");
    Set<Long> childrenBefore = childPids();
    CountDownLatch halted = new CountDownLatch(1);
    HostWedge wedge =
        new HostWedge(
            providerOf(mock(RegistrationLifecycle.class)),
            events,
            properties(marker),
            halted::countDown);

    wedge.run();

    assertThat(marker).exists();
    assertThat(published).hasSize(1);
    ApplicationEvent event = (ApplicationEvent) published.get(0);
    assertThat(event).isInstanceOf(AvailabilityChangeEvent.class);
    assertThat(((AvailabilityChangeEvent<?>) event).getState()).isEqualTo(LivenessState.BROKEN);

    assertThat(halted.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(Thread.getAllStackTraces().keySet())
        .noneMatch(thread -> thread.getName().equals("wedge-kill"));
    assertThat(childPids()).isEqualTo(childrenBefore);
  }

  @Test
  void aRetiringHostDrainsFirstThenExitsLikeAWedgeWithoutDeregisteringTwice() throws Exception {
    RegistrationLifecycle lifecycle = mock(RegistrationLifecycle.class);
    Path marker = dir.resolve("turnbase-wedged");
    CountDownLatch halted = new CountDownLatch(1);
    HostWedge host =
        new HostWedge(providerOf(lifecycle), events, properties(marker), halted::countDown);

    host.retire();
    host.retire();

    assertThat(halted.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(host.isRetiring()).isTrue();
    verify(lifecycle, org.mockito.Mockito.times(1)).stop();
    assertThat(marker).exists();
    assertThat(published).hasSize(1);
  }

  @Test
  void anUnwritableMarkerDoesNotStopTheExit() throws Exception {
    Path marker = dir.resolve("missing-directory").resolve("turnbase-wedged");
    CountDownLatch halted = new CountDownLatch(1);
    HostWedge wedge =
        new HostWedge(
            providerOf(mock(RegistrationLifecycle.class)),
            events,
            properties(marker),
            halted::countDown);

    wedge.run();

    assertThat(Files.exists(marker)).isFalse();
    assertThat(published).hasSize(1);
    assertThat(halted.await(10, TimeUnit.SECONDS)).isTrue();
  }

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
    CountDownLatch halted = new CountDownLatch(1);
    HostWedge wedge =
        new HostWedge(
            providerOf(lifecycle), events, properties(dir.resolve("m")), halted::countDown);

    Thread caller = new Thread(wedge);
    caller.setDaemon(true);
    caller.start();

    assertThat(halted.await(10, TimeUnit.SECONDS)).isTrue();
  }

  private static Set<Long> childPids() {
    return ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
  }
}
