package io.turnbase.gamehost.artifact;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * local-environment.md §7.9 — the same {@code turnbase.artifact-store.*} keys the Platform Monolith
 * binds. One credential, shared read-only by this service (secrets-and-configuration.md).
 */
@ConfigurationProperties(prefix = "turnbase.artifact-store")
public record ArtifactStoreProperties(
    String endpoint, String bucket, String accessKey, String secretKey) {}
