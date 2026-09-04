package io.turnbase.gamehost.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.turnbase.grpc.gamehost.v1.DeregisterRequest;
import io.turnbase.grpc.gamehost.v1.DeregisterResponse;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc;
import io.turnbase.grpc.gamehost.v1.RegisterRequest;
import io.turnbase.grpc.gamehost.v1.RegisterResponse;
import io.turnbase.grpc.gamehost.v1.ReportContextsRequest;
import io.turnbase.grpc.gamehost.v1.ReportContextsResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the client-side {@code Register} call runs, retries, and gives up within its budget
 * (IMP-M0-04's narrowed *Done when* clause, IMP-GATE-08) — against an in-process stub {@code
 * GameHostRegistryService}, not a live Platform Monolith. The real end-to-end, live-monolith check
 * is IMP-M3-05's.
 */
class OrchestrationRegistrationClientTest {

  private static final String SERVER_NAME = "orchestration-registration-client-test";

  private @Nullable Server server;
  private @Nullable ManagedChannel channel;

  @AfterEach
  void shutdown() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      server.shutdownNow();
    }
  }

  @Test
  void registersOnTheFirstAttempt() {
    RegisterResponse expected =
        RegisterResponse.newBuilder()
            .setHeartbeatIntervalSeconds(10)
            .setLeaseTtlSeconds(30)
            .build();
    startServer(new StubRegistryService(0, expected));

    RegisterResponse actual =
        newClient(Duration.ofSeconds(5), Duration.ofMillis(50)).registerWithRetry();

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void retriesUntilTheStubStopsFailing() {
    RegisterResponse expected =
        RegisterResponse.newBuilder()
            .setHeartbeatIntervalSeconds(10)
            .setLeaseTtlSeconds(30)
            .build();
    StubRegistryService stub = new StubRegistryService(2, expected);
    startServer(stub);

    RegisterResponse actual =
        newClient(Duration.ofSeconds(5), Duration.ofMillis(20)).registerWithRetry();

    assertThat(actual).isEqualTo(expected);
    assertThat(stub.attempts()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void givesUpOnceTheBudgetElapses() {
    startServer(new StubRegistryService(Integer.MAX_VALUE, RegisterResponse.getDefaultInstance()));

    assertThatThrownBy(
            () -> newClient(Duration.ofMillis(100), Duration.ofMillis(20)).registerWithRetry())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not complete within");
  }

  private OrchestrationRegistrationClient newClient(Duration budget, Duration interval) {
    channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
    return new OrchestrationRegistrationClient(
        GameHostRegistryServiceGrpc.newBlockingStub(channel),
        "test-instance",
        "test-instance:9090",
        budget,
        interval);
  }

  private void startServer(GameHostRegistryServiceGrpc.GameHostRegistryServiceImplBase service) {
    try {
      server =
          InProcessServerBuilder.forName(SERVER_NAME)
              .directExecutor()
              .addService(service)
              .build()
              .start();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Fails the first {@code failuresBeforeSuccess} calls with UNAVAILABLE, then succeeds. */
  private static final class StubRegistryService
      extends GameHostRegistryServiceGrpc.GameHostRegistryServiceImplBase {

    private final int failuresBeforeSuccess;
    private final RegisterResponse response;
    private final AtomicInteger attempts = new AtomicInteger();

    StubRegistryService(int failuresBeforeSuccess, RegisterResponse response) {
      this.failuresBeforeSuccess = failuresBeforeSuccess;
      this.response = response;
    }

    int attempts() {
      return attempts.get();
    }

    @Override
    public void register(
        RegisterRequest request, StreamObserver<RegisterResponse> responseObserver) {
      if (attempts.getAndIncrement() < failuresBeforeSuccess) {
        responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
        return;
      }
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }

    @Override
    public void reportContexts(
        ReportContextsRequest request, StreamObserver<ReportContextsResponse> responseObserver) {
      responseObserver.onNext(ReportContextsResponse.getDefaultInstance());
      responseObserver.onCompleted();
    }

    @Override
    public void deregister(
        DeregisterRequest request, StreamObserver<DeregisterResponse> responseObserver) {
      responseObserver.onNext(DeregisterResponse.getDefaultInstance());
      responseObserver.onCompleted();
    }
  }
}
