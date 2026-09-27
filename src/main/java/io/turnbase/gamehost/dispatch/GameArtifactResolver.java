package io.turnbase.gamehost.dispatch;

import java.nio.file.Path;

/**
 * Resolves a game's backend JAR to a local path. A narrow seam over {@code
 * io.turnbase.gamehost.artifact.ArtifactCache} so {@link SessionDispatcher} can be tested without a
 * real artifact fetch.
 */
interface GameArtifactResolver {

  Path resolve(String gameId, String gameVersion);
}
