package io.turnbase.gamehost.espresso;

import io.turnbase.gamehost.GameHostProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
class GameContextManagerConfig {

  @Bean
  GameContextManager gameContextManager(GameHostProperties properties) {
    return new GameContextManager(
        new EspressoContextFactory(properties.guestJavaHome(), properties.sdkJarPath()),
        properties.contextIdleEviction(),
        properties.memoryPressureFraction());
  }

  /** §6.3's eviction checks are periodic, not event-driven — both are cheap, idempotent scans. */
  @Component
  static class EvictionSchedule {

    private final GameContextManager contextManager;

    EvictionSchedule(GameContextManager contextManager) {
      this.contextManager = contextManager;
    }

    @Scheduled(fixedDelayString = "PT30S")
    void run() {
      contextManager.evictIdle();
      contextManager.evictUnderMemoryPressure();
    }
  }
}
