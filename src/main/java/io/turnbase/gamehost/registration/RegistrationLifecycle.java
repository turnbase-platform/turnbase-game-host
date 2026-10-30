package io.turnbase.gamehost.registration;

import static net.logstash.logback.argument.StructuredArguments.kv;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.turnbase.gamehost.GameHostProperties;
import io.turnbase.gamehost.espresso.GameContextManager;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc;
import java.net.InetAddress;
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
  private final Duration drainPeriod;
  private final String advertisedAddress;

  private volatile boolean running = false;
  private @Nullable ManagedChannel channel;
  private final GameContextManager contextManager;
  private @Nullable Thread worker;
  private @Nullable HostLivenessLoop loop;

  RegistrationLifecycle(GameHostProperties properties, GameContextManager contextManager) {
    this.contextManager = contextManager;
    this.drainPeriod = properties.drainPeriod();
    this.orchestrationAddress = properties.orchestrationAddress();
    this.instanceId = UUID.randomUUID().toString();
    this.advertisedAddress =
        AdvertisedAddress.resolve(
            properties.advertisedAddress(),
            properties.grpc().port(),
            () -> InetAddress.getLocalHost().getHostName());
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
    HostLivenessLoop l = loop;
    if (l != null && l.hasRegistered()) {
      drain(l);
    }
    running = false;
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

  /**
   * Scale-in (FR-SCALE-006, {@code load-testing-phase-b.md} §5.3): announce draining so that no new
   * session comes here, then keep serving the sessions this host has until they end or {@code
   * drain-period} passes. Whatever remains is reassigned by the Platform on its next input. The
   * gRPC server stops after this (a later lifecycle phase).
   */
  private void drain(HostLivenessLoop l) {
    l.beginDrain();
    long end = System.nanoTime() + drainPeriod.toNanos();
    log.info(
        "Draining {} {}",
        kv("activeSessions", contextManager.activeSessionCount()),
        kv("drainPeriod", drainPeriod.toString()));
    try {
      do {
        Thread.sleep(250);
      } while (contextManager.activeSessionCount() > 0 && System.nanoTime() < end);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    log.info("Drain over {}", kv("activeSessions", contextManager.activeSessionCount()));
  }

  /** Whether the first {@code Register} was acknowledged, i.e. the orchestrator can route here. */
  boolean hasRegistered() {
    HostLivenessLoop l = loop;
    return l != null && l.hasRegistered();
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
