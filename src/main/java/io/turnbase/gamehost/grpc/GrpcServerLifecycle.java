package io.turnbase.gamehost.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.turnbase.gamehost.GameHostProperties;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Binds {@link GameHostServiceImpl} to {@code 9090} (local-environment.md §1). A plain {@link
 * Server}, not a servlet container — the management port ({@code 8081}) is Spring's own embedded
 * server, entirely separate from this one.
 */
@Component
class GrpcServerLifecycle implements SmartLifecycle {

  private final GameHostServiceImpl gameHostService;
  private final GrpcCorrelationServerInterceptor correlationInterceptor;
  private final int port;
  private @Nullable Server server;

  GrpcServerLifecycle(
      GameHostServiceImpl gameHostService,
      GrpcCorrelationServerInterceptor correlationInterceptor,
      GameHostProperties properties) {
    this.gameHostService = gameHostService;
    this.correlationInterceptor = correlationInterceptor;
    this.port = properties.grpc().port();
  }

  @Override
  public void start() {
    try {
      server =
          ServerBuilder.forPort(port)
              .addService(gameHostService)
              .intercept(correlationInterceptor)
              .build()
              .start();
    } catch (IOException e) {
      throw new IllegalStateException("Game Host gRPC server failed to start on port " + port, e);
    }
  }

  @Override
  public void stop() {
    Server s = server;
    if (s == null) {
      return;
    }
    s.shutdown();
    try {
      if (!s.awaitTermination(30, TimeUnit.SECONDS)) {
        s.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      s.shutdownNow();
    }
  }

  /**
   * Stops after {@code RegistrationLifecycle}'s drain (default phase) and after the warm-up ({@code
   * MAX_VALUE - 1}): the sessions of a draining host are served until the period is over.
   */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 2;
  }

  @Override
  public boolean isRunning() {
    Server s = server;
    return s != null && !s.isShutdown();
  }

  /** The port actually bound — differs from the configured one when it was {@code 0}. */
  int boundPort() {
    Server s = server;
    if (s == null) {
      throw new IllegalStateException("gRPC server is not running");
    }
    return s.getPort();
  }
}
