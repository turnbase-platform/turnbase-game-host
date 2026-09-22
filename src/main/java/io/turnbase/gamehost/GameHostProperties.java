package io.turnbase.gamehost;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * local-environment.md §7.8 — the whole Game Host tunable surface, including {@code grpc.port}
 * (IMP-GATE-17). {@code sdkJarPath} is the guest classpath's non-game entry (game-host-internals.md
 * §4.2's {@code guestBridgeJar}) — a filesystem path provisioned into the image, the same pattern
 * as {@code guestJavaHome}, not a Maven dependency (IMP-M3-03 ships the real jar; this item only
 * builds the classpath composition). {@code artifactCacheDir}/{@code artifactCacheSizeLimit} back
 * FR-LOAD-003/004.
 */
@ConfigurationProperties(prefix = "turnbase.gamehost")
public record GameHostProperties(
    Duration handlerTimeout,
    Duration interruptEscalationGrace,
    Duration contextIdleEviction,
    String memoryPressureThreshold,
    Duration redisUnavailableWindow,
    int retryLimit,
    Grpc grpc,
    String guestJavaHome,
    String orchestrationAddress,
    String sdkJarPath,
    String artifactCacheDir,
    DataSize artifactCacheSizeLimit) {

  public record Grpc(int port, DataSize maxMessageSize) {}

  /**
   * FR-CTX-010's interpretation of {@link #memoryPressureThreshold}: a percentage string (e.g.
   * {@code "80%"}) as the fraction {@code GameContextManager} compares against current heap usage.
   */
  public double memoryPressureFraction() {
    String value = memoryPressureThreshold.strip();
    if (!value.endsWith("%")) {
      throw new IllegalArgumentException(
          "turnbase.gamehost.memory-pressure-threshold must end with '%', got: "
              + memoryPressureThreshold);
    }
    return Double.parseDouble(value.substring(0, value.length() - 1)) / 100.0;
  }
}
