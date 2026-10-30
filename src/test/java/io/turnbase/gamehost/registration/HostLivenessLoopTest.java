package io.turnbase.gamehost.registration;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.turnbase.gamehost.espresso.ContextKey;
import io.turnbase.grpc.gamehost.v1.DeregisterRequest;
import io.turnbase.grpc.gamehost.v1.DeregisterResponse;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc;
import io.turnbase.grpc.gamehost.v1.HeartbeatRequest;
import io.turnbase.grpc.gamehost.v1.HeartbeatResponse;
import io.turnbase.grpc.gamehost.v1.RegisterRequest;
import io.turnbase.grpc.gamehost.v1.RegisterResponse;
import io.turnbase.grpc.gamehost.v1.ReportContextsRequest;
import io.turnbase.grpc.gamehost.v1.ReportContextsResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** §11's client half: heartbeats keep flowing, NOT_FOUND re-registers, stop deregisters. */
class HostLivenessLoopTest {

  @Test
  void heartbeatsReportContextsAndReRegistersAfterNotFound() throws Exception {
    Recorder service = new Recorder();
    Server server =
        InProcessServerBuilder.forName("liveness-test").addService(service).build().start();
    ManagedChannel channel = InProcessChannelBuilder.forName("liveness-test").build();
    var blocking = GameHostRegistryServiceGrpc.newBlockingStub(channel);
    HostLivenessLoop loop =
        new HostLivenessLoop(
            new OrchestrationRegistrationClient(
                blocking, "i-1", "h:1", Duration.ofSeconds(5), Duration.ofMillis(20)),
            GameHostRegistryServiceGrpc.newStub(channel),
            blocking,
            "i-1",
            () -> Set.of(new ContextKey("g", "1.0.0")),
            () -> 2,
            () -> 40);
    Thread t = new Thread(loop::run);
    t.setDaemon(true);
    t.start();
    try {
      assertThat(service.reRegistered.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(service.registers.get()).isGreaterThanOrEqualTo(2);
      HeartbeatRequest beat = java.util.Objects.requireNonNull(service.lastBeat.get());
      assertThat(beat.getActiveSessions()).isEqualTo(2);
      assertThat(beat.getActiveContexts()).isEqualTo(1);
      assertThat(beat.getMemoryUsedPercent()).isEqualTo(40);
      assertThat(
              java.util.Objects.requireNonNull(service.lastContexts.get())
                  .getContexts(0)
                  .getGameId())
          .isEqualTo("g");
      loop.stop();
      assertThat(service.deregistered.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      t.interrupt();
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  @Test
  void beginDrainSendsAHeartbeatCarryingDrainingAtOnce() throws Exception {
    Recorder service = new Recorder();
    Server server =
        InProcessServerBuilder.forName("liveness-drain").addService(service).build().start();
    ManagedChannel channel = InProcessChannelBuilder.forName("liveness-drain").build();
    var blocking = GameHostRegistryServiceGrpc.newBlockingStub(channel);
    HostLivenessLoop loop =
        new HostLivenessLoop(
            new OrchestrationRegistrationClient(
                blocking, "i-2", "h:1", Duration.ofSeconds(5), Duration.ofMillis(20)),
            GameHostRegistryServiceGrpc.newStub(channel),
            blocking,
            "i-2",
            Set::of,
            () -> 0,
            () -> 0);
    Thread t = new Thread(loop::run);
    t.setDaemon(true);
    t.start();
    try {
      assertThat(service.reRegistered.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(java.util.Objects.requireNonNull(service.lastBeat.get()).getDraining()).isFalse();
      loop.beginDrain();
      // the interval is a second: only an immediate beat arrives this fast
      assertThat(service.drainingBeat.await(500, TimeUnit.MILLISECONDS)).isTrue();
    } finally {
      loop.stop();
      t.interrupt();
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  private static final class Recorder
      extends GameHostRegistryServiceGrpc.GameHostRegistryServiceImplBase {
    final AtomicInteger registers = new AtomicInteger();
    final AtomicInteger beats = new AtomicInteger();
    final AtomicReference<HeartbeatRequest> lastBeat = new AtomicReference<>();
    final AtomicReference<ReportContextsRequest> lastContexts = new AtomicReference<>();
    final CountDownLatch reRegistered = new CountDownLatch(1);
    final CountDownLatch drainingBeat = new CountDownLatch(1);
    final CountDownLatch deregistered = new CountDownLatch(1);

    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResponse> out) {
      if (registers.incrementAndGet() == 2) {
        reRegistered.countDown();
      }
      out.onNext(
          RegisterResponse.newBuilder()
              .setHeartbeatIntervalSeconds(1)
              .setLeaseTtlSeconds(3)
              .build());
      out.onCompleted();
    }

    @Override
    public StreamObserver<HeartbeatRequest> heartbeat(StreamObserver<HeartbeatResponse> out) {
      return new StreamObserver<>() {
        @Override
        public void onNext(HeartbeatRequest request) {
          lastBeat.set(request);
          if (request.getDraining()) {
            drainingBeat.countDown();
          }
          out.onNext(HeartbeatResponse.getDefaultInstance());
          if (beats.incrementAndGet() == 2) {
            out.onError(Status.NOT_FOUND.asRuntimeException());
          }
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}
      };
    }

    @Override
    public void reportContexts(
        ReportContextsRequest request, StreamObserver<ReportContextsResponse> out) {
      lastContexts.set(request);
      out.onNext(ReportContextsResponse.getDefaultInstance());
      out.onCompleted();
    }

    @Override
    public void deregister(DeregisterRequest request, StreamObserver<DeregisterResponse> out) {
      deregistered.countDown();
      out.onNext(DeregisterResponse.getDefaultInstance());
      out.onCompleted();
    }
  }
}
