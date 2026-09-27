package io.turnbase.gamehost.dispatch;

import io.turnbase.gamehost.espresso.ContextKey;
import org.jspecify.annotations.Nullable;

/** The Redis-persisted {@code session:{sessionId}} record (game-host-internals.md §8.1/§13). */
record SessionRecord(
    ContextKey contextKey,
    String entryPointFqcn,
    String stateJson,
    long lastAppliedSequence,
    Outcome lastOutcome,
    String lastEffectsJson,
    @Nullable String lastFailureKind,
    @Nullable String lastFailureMessage) {

  enum Outcome {
    OK,
    FAILED
  }
}
