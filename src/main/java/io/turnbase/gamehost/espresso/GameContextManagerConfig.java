package io.turnbase.gamehost.espresso;

import io.turnbase.gamehost.GameHostProperties;
import org.graalvm.polyglot.Engine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
class GameContextManagerConfig {

  /**
   * The one {@link Engine} every guest context runs on (IMP-M16-05, ADR-0019): compiled code and
   * caches are shared, the guest heap is not. Closed after {@link GameContextManager}, which
   * depends on it.
   */
  @Bean(destroyMethod = "close")
  Engine guestEngine() {
    return Engine.newBuilder().allowExperimentalOptions(true).build();
  }

  @Bean
  EspressoContextFactory espressoContextFactory(GameHostProperties properties, Engine guestEngine) {
    return new EspressoContextFactory(
        properties.guestJavaHome(), properties.sdkJarPath(), guestEngine);
  }

  @Bean
  GameContextManager gameContextManager(
      GameHostProperties properties,
      EspressoContextFactory contextFactory,
      BootWatchdog watchdog,
      ContextBudget budget) {
    return new GameContextManager(
        contextFactory,
        properties.contextIdleEviction(),
        properties.memoryPressureFraction(),
        watchdog,
        budget);
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
