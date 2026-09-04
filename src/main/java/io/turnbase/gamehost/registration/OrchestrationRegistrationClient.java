package io.turnbase.gamehost.registration;

import io.grpc.StatusRuntimeException;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc.GameHostRegistryServiceBlockingStub;
import io.turnbase.grpc.gamehost.v1.RegisterRequest;
import io.turnbase.grpc.gamehost.v1.RegisterResponse;
import java.time.Duration;

/**
 * Calls {@code Register} against {@code GameHostRegistryService}, retrying at a fixed interval
 * until it succeeds or {@code retryBudget} elapses (local-environment.md §3's 60-second budget).
 * Blocks the calling thread — run it off the application startup path.
 */
public final class OrchestrationRegistrationClient {

  private final GameHostRegistryServiceBlockingStub stub;
  private final String instanceId;
  private final String address;
  private final Duration retryBudget;
  private final Duration retryInterval;

  public OrchestrationRegistrationClient(
      GameHostRegistryServiceBlockingStub stub,
      String instanceId,
      String address,
      Duration retryBudget,
      Duration retryInterval) {
    this.stub = stub;
    this.instanceId = instanceId;
    this.address = address;
    this.retryBudget = retryBudget;
    this.retryInterval = retryInterval;
  }

  public RegisterResponse registerWithRetry() {
    long deadlineNanos = System.nanoTime() + retryBudget.toNanos();
    RegisterRequest request =
        RegisterRequest.newBuilder()
            .setInstanceId(instanceId)
            .setAddress(address)
            .addRuntimeTypes("jvm")
            .build();
    while (true) {
      try {
        return stub.register(request);
      } catch (StatusRuntimeException e) {
        if (System.nanoTime() >= deadlineNanos) {
          throw new IllegalStateException(
              "Game Host registration did not complete within the "
                  + retryBudget
                  + " budget (local-environment.md §3)",
              e);
        }
        sleepQuietly(retryInterval);
      }
    }
  }

  private static void sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while retrying registration", e);
    }
  }
}
