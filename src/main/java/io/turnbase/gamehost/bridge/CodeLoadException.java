package io.turnbase.gamehost.bridge;

/**
 * Thrown by {@link GameEntrypoint#createSession} when the manifest's entry-point FQCN cannot be
 * classloaded or does not implement {@code TurnBaseGame} (FR-LOAD-005). Unlike every other failure
 * path, this one is not folded into the {@code InvocationEnvelope}'s {@code failureKind}: the
 * entrypoint contract (game-host-internals.md §3.3, §7.2) narrows the envelope's {@code
 * failureKind} to {@code HANDLER_EXCEPTION}/{@code MALFORMED_OUTPUT}/{@code STATE_TOO_LARGE}, and a
 * session that never got an instance to invoke never reaches those hooks in the first place. So
 * this crosses the polyglot boundary as an ordinary thrown exception — the host shim sees it
 * wrapped in a {@code PolyglotException}, which the gRPC service (IMP-M3-04) translates to
 * transport {@code FAILED_PRECONDITION}.
 */
public final class CodeLoadException extends RuntimeException {

  CodeLoadException(String message) {
    super(message);
  }

  CodeLoadException(String message, Throwable cause) {
    super(message, cause);
  }
}
