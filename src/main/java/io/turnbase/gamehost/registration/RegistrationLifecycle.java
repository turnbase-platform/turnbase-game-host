package io.turnbase.gamehost.registration;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.turnbase.gamehost.GameHostProperties;
import io.turnbase.gamehost.espresso.GameContextManager;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs {@link HostLivenessLoop} (Register, Heartbeat, ReportContexts) on a background thread, so a
 * Platform Monolith that is not up yet never blocks this process from starting (IMP-GATE-08), and
 * {@code Deregister}s on shutdown.
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
  private final GameContextManager contextManager;
  private @Nullable Thread worker;
  private @Nullable HostLivenessLoop loop;

  RegistrationLifecycle(GameHostProperties properties, GameContextManager contextManager) {
    this.contextManager = contextManager;
    this.orchestrationAddress = properties.orchestrationAddress();
    this.instanceId = UUID.randomUUID().toString();
    this.advertisedAddress = resolveAdvertisedAddress(properties.grpc().port());
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
    HostLivenessLoop liveness =
        new HostLivenessLoop(
            client,
            GameHostRegistryServiceGrpc.newStub(channel),
            GameHostRegistryServiceGrpc.newBlockingStub(channel),
            instanceId,
            contextManager::liveContextKeys,
            contextManager::activeSessionCount,
            RegistrationLifecycle::heapUsedPercent);
    loop = liveness;
    worker = new Thread(liveness::run, "game-host-liveness");
    worker.setDaemon(true);
    running = true;
    worker.start();
  }

  private static int heapUsedPercent() {
    var heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    return heap.getMax() <= 0 ? 0 : (int) (100 * heap.getUsed() / heap.getMax());
  }

  @Override
  public void stop() {
    running = false;
    HostLivenessLoop l = loop;
    if (l != null) {
      l.stop();
    }
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
