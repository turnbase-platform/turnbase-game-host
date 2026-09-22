package io.turnbase.gamehost.artifact;

import java.nio.file.Path;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * The S3-compatible half of FR-LOAD-002 — MinIO locally, S3 in production, via {@link S3Client}'s
 * endpoint override ({@link ArtifactStoreConfig}). Read-only: this service only fetches (§7.1).
 */
@Component
final class S3ArtifactStore implements ArtifactStore {

  private final S3Client s3Client;
  private final ArtifactStoreProperties properties;

  S3ArtifactStore(S3Client s3Client, ArtifactStoreProperties properties) {
    this.s3Client = s3Client;
    this.properties = properties;
  }

  @Override
  public void download(String gameId, String version, Path destination) {
    String key = "artifacts/%s/%s/backend.jar".formatted(gameId, version);
    try {
      s3Client.getObject(
          GetObjectRequest.builder().bucket(properties.bucket()).key(key).build(),
          ResponseTransformer.toFile(destination));
    } catch (NoSuchKeyException e) {
      throw new ArtifactNotFoundException(gameId, version, e);
    }
  }
}
