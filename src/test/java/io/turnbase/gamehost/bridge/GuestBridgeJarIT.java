package io.turnbase.gamehost.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The shaded {@code guest-bridge} jar is what production puts on the guest classpath (§4.2), and
 * the Espresso tests run on the test classpath instead, so nothing else notices when the jar
 * packages the SDK but not the bridge itself — the failure IMP-M3-10's live round trip found.
 */
class GuestBridgeJarIT {

  @Test
  void shadedJarCarriesTheBridgeEntrypointAndTheSdk() throws IOException {
    Path jar;
    try (Stream<Path> files = Files.list(Path.of("target"))) {
      jar =
          files
              .filter(p -> p.getFileName().toString().endsWith("-guest-bridge.jar"))
              .findFirst()
              .orElseThrow();
    }
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      assertThat(jarFile.getEntry("io/turnbase/gamehost/bridge/GameEntrypoint.class")).isNotNull();
      assertThat(jarFile.getEntry("io/turnbase/gdk/context/InitContext.class")).isNotNull();
    }
  }
}
