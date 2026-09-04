package io.turnbase.gamehost.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.turnbase.gamehost.GameHostApplication;
import io.turnbase.grpc.gamehost.v1.CreateSessionRequest;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the real application through its own {@code main}, on ephemeral ports, and proves the
 * {@code GameHostService} server base class is actually bound to a running server — not just
 * resolved and compiled (IMP-GATE-03's server-side half).
 */
class GrpcServerIntegrationTest {

  private @Nullable ConfigurableApplicationContext context;
  private @Nullable ManagedChannel channel;

  @AfterEach
  void shutdown() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (context != null) {
      context.close();
    }
  }

  @Test
  void bootsAndServesGameHostServiceOnGrpc() {
    context =
        SpringApplication.run(
            GameHostApplication.class,
            "--management.server.port=0",
            "--turnbase.gamehost.grpc.port=0",
            "--turnbase.gamehost.orchestration-address=localhost:1");

    int grpcPort = context.getBean(GrpcServerLifecycle.class).boundPort();

    channel = ManagedChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build();
    GameHostServiceGrpc.GameHostServiceBlockingStub stub =
        GameHostServiceGrpc.newBlockingStub(channel);

    // No dispatch logic exists yet (IMP-M3-04); the generated base class answers UNIMPLEMENTED,
    // which is proof the server is bound and reachable, not a failure.
    StatusRuntimeException thrown =
        assertThrows(
            StatusRuntimeException.class,
            () -> stub.createSession(CreateSessionRequest.newBuilder().build()));
    assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED);
  }
}
