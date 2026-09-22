package io.turnbase.gamehost.espresso;

import java.io.File;
import java.nio.file.Path;

/**
 * game-host-internals.md §4.2/§7: the guest classpath is exactly two entries — the
 * platform-authored bridge jar (which carries the GDK API classes, i.e. "the SDK") and the game's
 * own backend JAR.
 */
final class GuestClasspathBuilder {

  private GuestClasspathBuilder() {}

  static String build(String sdkJarPath, Path gameArtifactPath) {
    return sdkJarPath + File.pathSeparator + gameArtifactPath;
  }
}
