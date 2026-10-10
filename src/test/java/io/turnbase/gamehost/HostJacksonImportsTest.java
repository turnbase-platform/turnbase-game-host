package io.turnbase.gamehost;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0016 §2 / R1: host-side code is Jackson 3 ({@code tools.jackson}). Only the guest bridge
 * package, which is shaded into the Jackson 2 guest, may import {@code
 * com.fasterxml.jackson.databind} or {@code .core}; {@code .annotation} is shared by both.
 */
class HostJacksonImportsTest {

  private static final Path MAIN = Path.of("src/main/java");
  private static final String BRIDGE = "io/turnbase/gamehost/bridge/";

  @Test
  void hostCodeOutsideTheGuestBridgeImportsNoJackson2Databind() throws IOException {
    List<String> offenders;
    try (Stream<Path> files = Files.walk(MAIN)) {
      offenders =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> !p.toString().replace('\\', '/').contains(BRIDGE))
              .filter(HostJacksonImportsTest::importsJackson2)
              .map(Path::toString)
              .toList();
    }
    assertThat(offenders).isEmpty();
  }

  private static boolean importsJackson2(Path file) {
    try {
      String source = Files.readString(file);
      return source.contains("com.fasterxml.jackson.databind")
          || source.contains("com.fasterxml.jackson.core");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
