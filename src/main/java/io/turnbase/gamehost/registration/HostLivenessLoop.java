package io.turnbase.gamehost.registration;

import io.grpc.stub.StreamObserver;
import io.turnbase.gamehost.espresso.ContextKey;
import io.turnbase.grpc.gamehost.v1.DeregisterRequest;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc.GameHostRegistryServiceBlockingStub;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc.GameHostRegistryServiceStub;
import io.turnbase.grpc.gamehost.v1.HeartbeatRequest;
import io.turnbase.grpc.gamehost.v1.HeartbeatResponse;
import io.turnbase.grpc.gamehost.v1.RegisterResponse;
import io.turnbase.grpc.gamehost.v1.ReportContextsRequest;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Game Host's side of the liveness contract (game-host-internals.md §11): {@code Register},
 * then a {@code Heartbeat} stream at the interval the response names, {@code ReportContexts} after
 * each registration and on every beat, and {@code Deregister} on shutdown. A stream that ends —
 * including {@code NOT_FOUND}, which means the registry no longer knows this instance — sends the
 * loop back to {@code Register}: the lease in Redis is authoritative, not the stream.
 */
public final class HostLivenessLoop {

  private static final Logger log = LoggerFactory.getLogger(HostLivenessLoop.class);

  private final OrchestrationRegistrationClient registration;
  private final GameHostRegistryServiceStub asyncStub;
  private final GameHostRegistryServiceBlockingStub blockingStub;
  private final String instanceId;
  private final Supplier<Set<ContextKey>> contexts;
  private final IntSupplier activeSessions;
  private final IntSupplier memoryUsedPercent;

  private volatile boolean running = true;
  private volatile boolean draining = false;

  public HostLivenessLoop(
      OrchestrationRegistrationClient registration,
      GameHostRegistryServiceStub asyncStub,
      GameHostRegistryServiceBlockingStub blockingStub,
      String instanceId,
      Supplier<Set<ContextKey>> contexts,
      IntSupplier activeSessions,
      IntSupplier memoryUsedPercent) {
    this.registration = registration;
    this.asyncStub = asyncStub;
    this.blockingStub = blockingStub;
    this.instanceId = instanceId;
    this.contexts = contexts;
    this.activeSessions = activeSessions;
    this.memoryUsedPercent = memoryUsedPercent;
  }

  /** Whether the registry asked this instance to drain (FR-SCALE-006). */
  public boolean isDraining() {
    return draining;
  }

  /** Runs until {@link #stop()}; blocks the calling thread. */
  public void run() {
    while (running) {
      RegisterResponse registered;
      try {
        registered = registration.registerWithRetry();
      } catch (IllegalStateException e) {
        if (!running) {
          return;
        }
        log.error("Game Host registration failed; trying again", e);
        continue;
      }
      log.info(
          "Registered (heartbeatIntervalSeconds={}, leaseTtlSeconds={})",
          registered.getHeartbeatIntervalSeconds(),
          registered.getLeaseTtlSeconds());
      Duration interval = Duration.ofSeconds(Math.max(1, registered.getHeartbeatIntervalSeconds()));
      try {
        beat(interval);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void beat(Duration interval) throws InterruptedException {
    CountDownLatch ended = new CountDownLatch(1);
    StreamObserver<HeartbeatRequest> stream =
        asyncStub.heartbeat(
            new StreamObserver<>() {
              @Override
              public void onNext(HeartbeatResponse response) {
                draining = response.getDrain();
              }

              @Override
              public void onError(Throwable t) {
                log.warn("Heartbeat stream ended: {}", t.toString());
                ended.countDown();
              }

              @Override
              public void onCompleted() {
                ended.countDown();
              }
            });
    try {
      while (running) {
        reportContexts();
        stream.onNext(
            HeartbeatRequest.newBuilder()
                .setInstanceId(instanceId)
                .setActiveSessions(activeSessions.getAsInt())
                .setActiveContexts(contexts.get().size())
                .setMemoryUsedPercent(memoryUsedPercent.getAsInt())
                .build());
        if (ended.await(interval.toMillis(), TimeUnit.MILLISECONDS)) {
          return;
        }
      }
    } catch (RuntimeException e) {
      log.warn("Heartbeat failed; registering again", e);
    } finally {
      try {
        stream.onCompleted();
      } catch (RuntimeException ignored) {
        // the stream is already dead
      }
    }
  }

  private void reportContexts() {
    ReportContextsRequest.Builder request =
        ReportContextsRequest.newBuilder().setInstanceId(instanceId);
    contexts
        .get()
        .forEach(
            key ->
                request.addContexts(
                    io.turnbase.grpc.gamehost.v1.ContextKey.newBuilder()
                        .setGameId(key.gameId())
                        .setGameVersion(key.version())));
    blockingStub.reportContexts(request.build());
  }

  /** Stops the loop and tells the registry to route nothing further here (FR-REG-004). */
  public void stop() {
    running = false;
    try {
      blockingStub
          .withDeadlineAfter(3, TimeUnit.SECONDS)
          .deregister(DeregisterRequest.newBuilder().setInstanceId(instanceId).build());
    } catch (RuntimeException e) {
      log.warn("Deregister failed; the lease will expire on its own", e);
    }
  }
}
