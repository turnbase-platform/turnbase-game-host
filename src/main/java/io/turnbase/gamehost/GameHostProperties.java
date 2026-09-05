package io.turnbase.gamehost;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * local-environment.md §7.8 — the whole Game Host tunable surface, including {@code grpc.port}
 * (IMP-GATE-17). {@code memoryPressureThreshold} stays a raw {@code String} (e.g. {@code "80%"}):
 * FR-CTX-010 owns how that percentage is interpreted, and no document assigns it a numeric type
 * yet.
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
    String orchestrationAddress) {

  public record Grpc(int port, DataSize maxMessageSize) {}
}
