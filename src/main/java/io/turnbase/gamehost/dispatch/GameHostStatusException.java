package io.turnbase.gamehost.dispatch;

import io.grpc.Status;

/**
 * An intentional transport/infrastructure failure (the two-tier error model's native-status half —
 * orchestration-gamehost-protocol.md "Error model") to surface as-is at the gRPC boundary.
 */
public final class GameHostStatusException extends RuntimeException {

  private final Status status;

  public GameHostStatusException(Status status) {
    super(status.getDescription());
    this.status = status;
  }

  public Status status() {
    return status;
  }
}
