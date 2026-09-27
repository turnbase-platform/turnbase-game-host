package io.turnbase.gamehost.dispatch;

import io.turnbase.gamehost.artifact.ArtifactCache;
import io.turnbase.gamehost.espresso.GameContextManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
class SessionDispatcherConfig {

  @Bean
  GameArtifactResolver gameArtifactResolver(ArtifactCache artifactCache) {
    return artifactCache::get;
  }

  @Bean
  SessionRecordStore sessionRecordStore(StringRedisTemplate redisTemplate) {
    return new SessionRecordStore(redisTemplate);
  }

  @Bean
  SessionDispatcher sessionDispatcher(
      GameContextManager contextManager,
      GameArtifactResolver artifactResolver,
      SessionRecordStore recordStore) {
    return new SessionDispatcher(contextManager, artifactResolver, recordStore);
  }
}
