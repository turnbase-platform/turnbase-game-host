package io.turnbase.gamehost.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.turnbase.grpc.gamehost.v1.CreateSessionRequest;
import io.turnbase.grpc.gamehost.v1.CreateSessionResponse;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * IMP-GATE-18's other half: proves {@link GrpcCorrelationServerInterceptor} restores whichever
 * correlation IDs arrive as gRPC metadata into the handling thread's MDC, and clears them
 * afterwards — the receiving side of turnbase-platform's {@code GrpcCorrelationClientInterceptor}.
 */
class GrpcCorrelationServerInterceptorTest {

  private static final String SERVER_NAME = "grpc-correlation-server-interceptor-test";

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
    MDC.clear();
  }

  @Test
  void restoresPresentHeadersIntoMdcAndClearsThemAfterward() throws IOException {
    Map<String, String> seenDuringHandling = new ConcurrentHashMap<>();
    server =
        InProcessServerBuilder.forName(SERVER_NAME)
            .directExecutor()
            .addService(new MdcCapturingService(seenDuringHandling))
            .intercept(new GrpcCorrelationServerInterceptor())
            .build()
            .start();
    channel =
        InProcessChannelBuilder.forName(SERVER_NAME)
            .directExecutor()
            .intercept(headerSettingInterceptor(Map.of("requestId", "req-1", "roomId", "room-1")))
            .build();

    GameHostServiceGrpc.newBlockingStub(channel)
        .createSession(CreateSessionRequest.newBuilder().build());

    assertThat(seenDuringHandling)
        .containsEntry("requestId", "req-1")
        .containsEntry("roomId", "room-1");
    assertThat(seenDuringHandling).doesNotContainKeys("userId", "sessionId", "gameId");
    assertThat(MDC.get("requestId")).isNull();
  }

  private static ClientInterceptor headerSettingInterceptor(Map<String, String> mdcValues) {
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            for (GrpcCorrelationKeys key : GrpcCorrelationKeys.values()) {
              String value = mdcValues.get(key.mdcKey());
              if (value != null) {
                headers.put(key.metadataKey(), value);
              }
            }
            super.start(responseListener, headers);
          }
        };
      }
    };
  }

  private static final class MdcCapturingService
      extends GameHostServiceGrpc.GameHostServiceImplBase {
    private final Map<String, String> sink;

    MdcCapturingService(Map<String, String> sink) {
      this.sink = sink;
    }

    @Override
    public void createSession(
        CreateSessionRequest request, StreamObserver<CreateSessionResponse> responseObserver) {
      for (GrpcCorrelationKeys key : GrpcCorrelationKeys.values()) {
        String value = MDC.get(key.mdcKey());
        if (value != null) {
          sink.put(key.mdcKey(), value);
        }
      }
      responseObserver.onNext(CreateSessionResponse.getDefaultInstance());
      responseObserver.onCompleted();
    }
  }
}
