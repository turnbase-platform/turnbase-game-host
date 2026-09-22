package io.turnbase.gamehost.espresso;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** §7: the guest classpath contains the game JAR and the SDK, and nothing else. */
class GuestClasspathBuilderTest {

  @Test
  void containsExactlyTheSdkAndTheGameJar() {
    Path gameJar = Path.of("/var/lib/turnbase/game-host/artifact-cache/tic-tac-toe_1.0.0.jar");

    String classpath = GuestClasspathBuilder.build("/opt/turnbase/guest-bridge.jar", gameJar);

    assertThat(classpath.split(File.pathSeparator))
        .containsExactlyInAnyOrder("/opt/turnbase/guest-bridge.jar", gameJar.toString());
  }
}
