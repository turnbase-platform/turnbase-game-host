package io.turnbase.gamehost.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * §7.1 against a real MinIO — conventions.md §3: Testcontainers, never a hand-started stack. Same
 * container image as turnbase-platform's identical MinIO-backed test.
 */
@Testcontainers
class S3ArtifactStoreIT {

  @Container
  private static final MinIOContainer MINIO =
      new MinIOContainer(
          DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
              .asCompatibleSubstituteFor("minio/minio"));

  private static final String BUCKET = "turnbase-bundles";

  @TempDir Path destinationDir;

  private S3Client client() {
    return S3Client.builder()
        .endpointOverride(URI.create(MINIO.getS3URL()))
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
        .forcePathStyle(true)
        .build();
  }

  @Test
  void downloadsAnExistingArtifactByGameIdAndVersion() throws Exception {
    try (S3Client client = client()) {
      client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
      client.putObject(
          PutObjectRequest.builder()
              .bucket(BUCKET)
              .key("artifacts/tic-tac-toe/1.0.0/backend.jar")
              .build(),
          software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[] {1, 2, 3}));

      ArtifactStore store =
          new S3ArtifactStore(
              client,
              new ArtifactStoreProperties(
                  MINIO.getS3URL(), BUCKET, MINIO.getUserName(), MINIO.getPassword()));
      Path destination = destinationDir.resolve("backend.jar");

      store.download("tic-tac-toe", "1.0.0", destination);

      assertThat(Files.readAllBytes(destination)).containsExactly(1, 2, 3);
    }
  }

  @Test
  void throwsArtifactNotFoundForAMissingKey() {
    try (S3Client client = client()) {
      client.createBucket(CreateBucketRequest.builder().bucket(BUCKET + "-empty").build());
      ArtifactStore store =
          new S3ArtifactStore(
              client,
              new ArtifactStoreProperties(
                  MINIO.getS3URL(), BUCKET + "-empty", MINIO.getUserName(), MINIO.getPassword()));

      assertThatThrownBy(
              () -> store.download("no-such-game", "1.0.0", destinationDir.resolve("backend.jar")))
          .isInstanceOf(ArtifactNotFoundException.class);
    }
  }
}
