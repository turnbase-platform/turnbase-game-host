package io.turnbase.gamehost.espresso;

import static org.assertj.core.api.Assertions.assertThat;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * IMP-M0-04's *Done when* clause: a trivial guest context can be created and disposed. Uses the
 * running GraalVM CE 21 JDK as the guest home ({@code .sdkmanrc} pins the same build), rather than
 * the container's {@code /opt/graalvm-guest-jdk21} — this proves context creation, not the packaged
 * image, which is Dockerfile's job.
 */
class EspressoContextLifecycleTest {

  @Test
  void createsAndDisposesATrivialGuestContext() {
    EspressoContextFactory factory = new EspressoContextFactory(System.getProperty("java.home"));

    try (Context context = factory.create()) {
      context.initialize("java");
      assertThat(context.getEngine().getLanguages()).containsKey("java");
    }
  }
}
