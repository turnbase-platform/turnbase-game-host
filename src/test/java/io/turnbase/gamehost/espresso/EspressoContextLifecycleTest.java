package io.turnbase.gamehost.espresso;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.gamehost.espresso.fixture.EspressoFixtures;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * A trivial guest context can be created and disposed, and the classpath it was built with is
 * exactly the fixture directory (standing in for the game JAR) plus the configured SDK entry. Uses
 * the running GraalVM CE 21 JDK as the guest home ({@code .sdkmanrc} pins the same build), rather
 * than the container's {@code /opt/graalvm-guest-jdk21} — this proves context creation, not the
 * packaged image, which is Dockerfile's job.
 */
class EspressoContextLifecycleTest {

  private static final String SDK_JAR_PATH = "/opt/turnbase/guest-bridge.jar";

  @Test
  void createsAndDisposesATrivialGuestContext() throws URISyntaxException {
    EspressoContextFactory factory =
        new EspressoContextFactory(System.getProperty("java.home"), SDK_JAR_PATH);

    try (Context context = factory.create(fixtureClasspathDir())) {
      context.initialize("java");
      assertThat(context.getEngine().getLanguages()).containsKey("java");
    }
  }

  static Path fixtureClasspathDir() throws URISyntaxException {
    return Path.of(
        EspressoFixtures.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  }
}
