package io.turnbase.gamehost.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.turnbase.gamehost.GameHostApplication;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.ProcessInputRequest;
import io.turnbase.grpc.gamehost.v1.TerminateSessionRequest;
import io.turnbase.grpc.gamehost.v1.TerminateSessionResponse;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the real application through its own {@code main}, on ephemeral ports, and proves the
 * {@code GameHostService} server base class is actually bound to a running server (IMP-GATE-03's
 * server-side half) and that real dispatch logic (IMP-M3-04) is wired to it: {@code
 * TerminateSession} is idempotent for a session this instance never created (game-host-internals.md
 * §9.1), and {@code ProcessInput} for an unknown session is scoped {@code NOT_FOUND} — never {@code
 * TerminateSession} (orchestration-gamehost-protocol.md "Error model").
 */
@Testcontainers
class GrpcServerIntegrationIT {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

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

  private GameHostServiceGrpc.GameHostServiceBlockingStub bootAndConnect() {
    context =
        SpringApplication.run(
            GameHostApplication.class,
            "--management.server.port=0",
            "--turnbase.gamehost.grpc.port=0",
            "--turnbase.gamehost.orchestration-address=localhost:1",
            "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379));

    int grpcPort = context.getBean(GrpcServerLifecycle.class).boundPort();
    channel = ManagedChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build();
    return GameHostServiceGrpc.newBlockingStub(channel);
  }

  @Test
  void bootsAndServesGameHostServiceOnGrpc() {
    GameHostServiceGrpc.GameHostServiceBlockingStub stub = bootAndConnect();

    TerminateSessionResponse response =
        stub.terminateSession(
            TerminateSessionRequest.newBuilder()
                .setSessionId(UUID.randomUUID().toString())
                .build());

    assertThat(response.getSessionId()).isNotEmpty();
  }

  @Test
  void processInputForAnUnknownSessionIsNotFound() {
    GameHostServiceGrpc.GameHostServiceBlockingStub stub = bootAndConnect();

    StatusRuntimeException thrown =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                stub.processInput(
                    ProcessInputRequest.newBuilder()
                        .setSessionId(UUID.randomUUID().toString())
                        .setSequence(1)
                        .build()));

    assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
  }
}
