package io.turnbase.gamehost.registration;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc;
import io.turnbase.grpc.gamehost.v1.RegisterResponse;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Attempts {@code Register} on a background thread at startup, so a Platform Monolith that has not
 * implemented {@code GameHostRegistryService} yet (IMP-M3-05) never blocks this process from
 * starting (IMP-GATE-08). The dialable pod address and the real heartbeat/drain loop are
 * IMP-M3-05's counterpart, IMP-M3-04.
 */
@Component
class RegistrationLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(RegistrationLifecycle.class);

  // local-environment.md §3: monolith readiness -> Register acknowledged, budgeted at 60s.
  private static final Duration REGISTRATION_BUDGET = Duration.ofSeconds(60);
  private static final Duration RETRY_INTERVAL = Duration.ofSeconds(2);

  private final String orchestrationAddress;
  private final String instanceId;
  private final String advertisedAddress;

  private volatile boolean running = false;
  private @Nullable ManagedChannel channel;
  private @Nullable Thread worker;

  RegistrationLifecycle(
      @Value("${turnbase.gamehost.orchestration-address}") String orchestrationAddress,
      @Value("${turnbase.gamehost.grpc.port:9090}") int grpcPort) {
    this.orchestrationAddress = orchestrationAddress;
    this.instanceId = UUID.randomUUID().toString();
    this.advertisedAddress = resolveAdvertisedAddress(grpcPort);
  }

  @Override
  public void start() {
    channel = ManagedChannelBuilder.forTarget(orchestrationAddress).usePlaintext().build();
    OrchestrationRegistrationClient client =
        new OrchestrationRegistrationClient(
            GameHostRegistryServiceGrpc.newBlockingStub(channel),
            instanceId,
            advertisedAddress,
            REGISTRATION_BUDGET,
            RETRY_INTERVAL);
    worker = new Thread(() -> attemptRegistration(client), "game-host-registration");
    worker.setDaemon(true);
    running = true;
    worker.start();
  }

  private void attemptRegistration(OrchestrationRegistrationClient client) {
    try {
      RegisterResponse response = client.registerWithRetry();
      log.info(
          "Registered with the Platform Monolith at {} (heartbeatIntervalSeconds={},"
              + " leaseTtlSeconds={})",
          orchestrationAddress,
          response.getHeartbeatIntervalSeconds(),
          response.getLeaseTtlSeconds());
    } catch (RuntimeException e) {
      log.error("Game Host registration against {} failed", orchestrationAddress, e);
    }
  }

  @Override
  public void stop() {
    running = false;
    Thread w = worker;
    if (w != null) {
      w.interrupt();
    }
    ManagedChannel c = channel;
    if (c != null) {
      c.shutdownNow();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private static String resolveAdvertisedAddress(int grpcPort) {
    try {
      return InetAddress.getLocalHost().getHostName() + ":" + grpcPort;
    } catch (UnknownHostException e) {
      return "localhost:" + grpcPort;
    }
  }
}
