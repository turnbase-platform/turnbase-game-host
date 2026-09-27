package io.turnbase.gamehost.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.turnbase.gamehost.dispatch.GameHostStatusException;
import io.turnbase.gamehost.dispatch.SessionDispatcher;
import io.turnbase.grpc.gamehost.v1.CreateSessionRequest;
import io.turnbase.grpc.gamehost.v1.CreateSessionResponse;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.ProcessInputRequest;
import io.turnbase.grpc.gamehost.v1.ProcessInputResponse;
import io.turnbase.grpc.gamehost.v1.ResyncRequest;
import io.turnbase.grpc.gamehost.v1.ResyncResponse;
import io.turnbase.grpc.gamehost.v1.TerminateSessionRequest;
import io.turnbase.grpc.gamehost.v1.TerminateSessionResponse;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Wires the four {@code GameHostService} RPCs to {@link SessionDispatcher} (IMP-M3-04,
 * game-host-internals.md §9). Two-tier error model (orchestration-gamehost-protocol.md "Error
 * model"): {@link GameHostStatusException} carries an intentional transport status; any other
 * runtime failure becomes {@code INTERNAL} rather than crashing the call silently.
 */
@Component
class GameHostServiceImpl extends GameHostServiceGrpc.GameHostServiceImplBase {

  private final SessionDispatcher dispatcher;

  GameHostServiceImpl(SessionDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Override
  public void createSession(
      CreateSessionRequest request, StreamObserver<CreateSessionResponse> responseObserver) {
    dispatch(
        responseObserver,
        () ->
            CreateSessionResponse.newBuilder()
                .setResult(dispatcher.createSession(request))
                .build());
  }

  @Override
  public void processInput(
      ProcessInputRequest request, StreamObserver<ProcessInputResponse> responseObserver) {
    dispatch(
        responseObserver,
        () ->
            ProcessInputResponse.newBuilder().setResult(dispatcher.processInput(request)).build());
  }

  @Override
  public void resync(ResyncRequest request, StreamObserver<ResyncResponse> responseObserver) {
    dispatch(responseObserver, () -> dispatcher.resync(request));
  }

  @Override
  public void terminateSession(
      TerminateSessionRequest request, StreamObserver<TerminateSessionResponse> responseObserver) {
    dispatch(responseObserver, () -> dispatcher.terminateSession(request));
  }

  private static <T> void dispatch(StreamObserver<T> responseObserver, Supplier<T> action) {
    try {
      responseObserver.onNext(action.get());
      responseObserver.onCompleted();
    } catch (GameHostStatusException e) {
      responseObserver.onError(e.status().asRuntimeException());
    } catch (RuntimeException e) {
      responseObserver.onError(
          Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException());
    }
  }
}
