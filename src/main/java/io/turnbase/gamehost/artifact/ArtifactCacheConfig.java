package io.turnbase.gamehost.artifact;

import io.turnbase.gamehost.GameHostProperties;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ArtifactCacheConfig {

  @Bean
  ArtifactCache artifactCache(ArtifactStore artifactStore, GameHostProperties properties) {
    return new ArtifactCache(
        artifactStore,
        Path.of(properties.artifactCacheDir()),
        properties.artifactCacheSizeLimit().toBytes());
  }
}
