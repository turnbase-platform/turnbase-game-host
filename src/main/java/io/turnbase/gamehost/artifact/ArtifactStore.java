package io.turnbase.gamehost.artifact;

import java.nio.file.Path;

/**
 * The Artifact Store abstraction (FR-LOAD-002): local filesystem in development, S3-compatible
 * (MinIO, S3) in every other environment, both reached behind this one interface.
 */
public interface ArtifactStore {

  /**
   * Downloads the backend JAR for {@code (gameId, version)} to {@code destination}, per the {@code
   * artifacts/{gameId}/{version}/backend.jar} key convention (bundle-format.md §5.1).
   *
   * @throws ArtifactNotFoundException if no such object exists
   */
  void download(String gameId, String version, Path destination);
}
