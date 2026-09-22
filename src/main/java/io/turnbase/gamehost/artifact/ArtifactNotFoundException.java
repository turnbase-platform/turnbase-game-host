package io.turnbase.gamehost.artifact;

/** No backend JAR exists in the Artifact Store for the requested game version. */
public final class ArtifactNotFoundException extends RuntimeException {

  public ArtifactNotFoundException(String gameId, String version, Throwable cause) {
    super("No artifact for game " + gameId + " version " + version, cause);
  }
}
