package io.turnbase.gamehost.grpc;

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * observability.md §3's gRPC-out MDC boundary, receiving side: restores whichever of {@link
 * GrpcCorrelationKeys} the caller's {@code ClientInterceptor} put on the metadata (FR-OBS-003).
 * Wired onto the real {@link GrpcServerLifecycle}, so every inbound call — including today's {@code
 * UNIMPLEMENTED} {@code GameHostService} stubs — carries whatever correlation the caller sent,
 * ready for the dispatch logic IMP-M3-04 adds.
 */
@Component
final class GrpcCorrelationServerInterceptor implements ServerInterceptor {

  @Override
  public <ReqT, RespT> Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    Map<String, String> captured = new HashMap<>();
    for (GrpcCorrelationKeys key : GrpcCorrelationKeys.values()) {
      String value = headers.get(key.metadataKey());
      if (value != null) {
        captured.put(key.mdcKey(), value);
      }
    }
    Listener<ReqT> delegate = next.startCall(call, headers);
    return new SimpleForwardingServerCallListener<>(delegate) {
      @Override
      public void onMessage(ReqT message) {
        withMdc(() -> super.onMessage(message));
      }

      @Override
      public void onHalfClose() {
        withMdc(super::onHalfClose);
      }

      private void withMdc(Runnable action) {
        captured.forEach(MDC::put);
        try {
          action.run();
        } finally {
          captured.keySet().forEach(MDC::remove);
        }
      }
    };
  }
}
