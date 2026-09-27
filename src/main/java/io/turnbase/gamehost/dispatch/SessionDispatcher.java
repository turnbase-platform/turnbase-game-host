package io.turnbase.gamehost.dispatch;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.turnbase.gamehost.espresso.ContextKey;
import io.turnbase.gamehost.espresso.GameContextManager;
import io.turnbase.gamehost.espresso.ManagedContext;
import io.turnbase.gamehost.shim.GameBridgeClient;
import io.turnbase.grpc.gamehost.v1.CreateSessionRequest;
import io.turnbase.grpc.gamehost.v1.GameFailure;
import io.turnbase.grpc.gamehost.v1.PlayerLeftReason;
import io.turnbase.grpc.gamehost.v1.ProcessInputRequest;
import io.turnbase.grpc.gamehost.v1.ProcessResult;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import io.turnbase.grpc.gamehost.v1.ResyncRequest;
import io.turnbase.grpc.gamehost.v1.ResyncResponse;
import io.turnbase.grpc.gamehost.v1.TerminateSessionRequest;
import io.turnbase.grpc.gamehost.v1.TerminateSessionResponse;
import io.turnbase.grpc.gamehost.v1.ViewerRole;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.graalvm.polyglot.PolyglotException;

/**
 * Dispatches the four {@code GameHostService} RPCs against the context/bridge/Redis machinery
 * (game-host-internals.md §9.1). Per-session single-flight locking (FR-SESS-006) serializes inputs
 * for one session; different sessions run concurrently.
 */
public final class SessionDispatcher {

  private final GameContextManager contextManager;
  private final GameArtifactResolver artifactResolver;
  private final SessionRecordStore recordStore;

  /**
   * This host's view of which sessions it has bound locally — absent here means recovery (§9.3).
   */
  private final Map<String, ContextKey> localSessions = new ConcurrentHashMap<>();

  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  /** Sessions terminated on this host — a discarded in-flight result must never resurrect Redis. */
  private final Set<String> terminated = ConcurrentHashMap.newKeySet();

  public SessionDispatcher(
      GameContextManager contextManager,
      GameArtifactResolver artifactResolver,
      SessionRecordStore recordStore) {
    this.contextManager = contextManager;
    this.artifactResolver = artifactResolver;
    this.recordStore = recordStore;
  }

  public ProcessResult createSession(CreateSessionRequest request) {
    String sessionId = request.getSessionId();
    return withLock(
        sessionId,
        () -> {
          Optional<SessionRecord> existing = recordStore.find(sessionId);
          if (existing.isPresent()) {
            return replay(sessionId, 0, existing.get(), true);
          }

          ContextKey key =
              new ContextKey(
                  request.getContext().getGameId(), request.getContext().getGameVersion());
          ManagedContext context = resolve(key);
          String teamsJson = TeamsJson.encode(request.getTeamsList());
          String parametersJson = request.getParameters().toStringUtf8();

          String envelopeJson;
          try {
            envelopeJson =
                context
                    .bridgeClient()
                    .createSession(
                        sessionId, request.getEntryPointFqcn(), teamsJson, parametersJson);
          } catch (PolyglotException e) {
            throw new GameHostStatusException(
                Status.FAILED_PRECONDITION
                    .withDescription("code load failed: " + e.getMessage())
                    .withCause(e));
          }

          EnvelopeCodec.ParsedInvocation parsed = EnvelopeCodec.decodeInvocation(envelopeJson);
          if (!parsed.ok()) {
            GameFailure failure = Objects.requireNonNull(parsed.failure());
            return ProcessResult.newBuilder()
                .setSessionId(sessionId)
                .setSequence(0)
                .setStatus(ProcessStatus.PROCESS_STATUS_FAILED)
                .setFailure(failure)
                .build();
          }

          bindLocally(sessionId, key);
          recordStore.save(
              sessionId,
              new SessionRecord(
                  key,
                  request.getEntryPointFqcn(),
                  parsed.stateJson(),
                  0,
                  SessionRecord.Outcome.OK,
                  parsed.effectsRawJson(),
                  null,
                  null));

          return ProcessResult.newBuilder()
              .setSessionId(sessionId)
              .setSequence(0)
              .setStatus(ProcessStatus.PROCESS_STATUS_OK)
              .addAllEffects(parsed.effects())
              .build();
        });
  }

  public ProcessResult processInput(ProcessInputRequest request) {
    String sessionId = request.getSessionId();
    long sequence = request.getSequence();
    return withLock(
        sessionId,
        () -> {
          SessionRecord record =
              recordStore
                  .find(sessionId)
                  .orElseThrow(
                      () ->
                          new GameHostStatusException(
                              Status.NOT_FOUND.withDescription("no session record: " + sessionId)));

          if (sequence <= record.lastAppliedSequence()) {
            return replay(sessionId, sequence, record, false);
          }

          ManagedContext context = recover(sessionId, record);
          String envelopeJson = invoke(context.bridgeClient(), request, record.stateJson());
          EnvelopeCodec.ParsedInvocation parsed = EnvelopeCodec.decodeInvocation(envelopeJson);

          SessionRecord updated;
          ProcessResult.Builder result =
              ProcessResult.newBuilder().setSessionId(sessionId).setSequence(sequence);
          if (parsed.ok()) {
            updated =
                new SessionRecord(
                    record.contextKey(),
                    record.entryPointFqcn(),
                    parsed.stateJson(),
                    sequence,
                    SessionRecord.Outcome.OK,
                    parsed.effectsRawJson(),
                    null,
                    null);
            result.setStatus(ProcessStatus.PROCESS_STATUS_OK).addAllEffects(parsed.effects());
          } else {
            GameFailure failure = Objects.requireNonNull(parsed.failure());
            updated =
                new SessionRecord(
                    record.contextKey(),
                    record.entryPointFqcn(),
                    record.stateJson(),
                    sequence,
                    SessionRecord.Outcome.FAILED,
                    "[]",
                    failure.getKind().name(),
                    failure.getMessage());
            result.setStatus(ProcessStatus.PROCESS_STATUS_FAILED).setFailure(failure);
          }

          if (!terminated.contains(sessionId)) {
            recordStore.save(sessionId, updated);
          }
          return result.build();
        });
  }

  public ResyncResponse resync(ResyncRequest request) {
    String sessionId = request.getSessionId();
    return withLock(
        sessionId,
        () -> {
          SessionRecord record =
              recordStore
                  .find(sessionId)
                  .orElseThrow(
                      () ->
                          new GameHostStatusException(
                              Status.NOT_FOUND.withDescription("no session record: " + sessionId)));

          ManagedContext context = recover(sessionId, record);
          String viewerJson = request.hasViewer() ? TeamsJson.encode(request.getViewer()) : "null";
          String role =
              request.getRole() == ViewerRole.VIEWER_ROLE_SPECTATOR ? "SPECTATOR" : "PLAYER";

          String envelopeJson =
              context.bridgeClient().resync(sessionId, record.stateJson(), viewerJson, role);
          EnvelopeCodec.ParsedView parsed = EnvelopeCodec.decodeView(envelopeJson);

          ResyncResponse.Builder response =
              ResyncResponse.newBuilder()
                  .setSessionId(sessionId)
                  .setAsOfSequence(record.lastAppliedSequence());
          if (parsed.ok()) {
            response
                .setStatus(ProcessStatus.PROCESS_STATUS_OK)
                .setView(ByteString.copyFromUtf8(parsed.viewJson()));
          } else {
            response
                .setStatus(ProcessStatus.PROCESS_STATUS_FAILED)
                .setFailure(Objects.requireNonNull(parsed.failure()));
          }
          return response.build();
        });
  }

  /**
   * Non-blocking and idempotent (§9.1): never waits on the per-session lock. Deletes the Redis
   * record and returns immediately; an in-flight handler's result, once its lock frees, is
   * discarded rather than persisted (see the {@code terminated} guard in {@link #processInput}).
   */
  public TerminateSessionResponse terminateSession(TerminateSessionRequest request) {
    String sessionId = request.getSessionId();
    terminated.add(sessionId);
    recordStore.delete(sessionId);
    ContextKey key = localSessions.remove(sessionId);
    if (key != null) {
      contextManager.sessionEnded(key);
    }
    return TerminateSessionResponse.newBuilder().setSessionId(sessionId).build();
  }

  private ManagedContext resolve(ContextKey key) {
    Path artifactPath = artifactResolver.resolve(key.gameId(), key.version());
    return contextManager.getOrCreate(key, artifactPath);
  }

  /** §9.3 crash recovery: binds a session record found in Redis but not in this host's memory. */
  private ManagedContext recover(String sessionId, SessionRecord record) {
    ContextKey key = record.contextKey();
    boolean needsRestore = !localSessions.containsKey(sessionId);
    ManagedContext context = resolve(key);
    if (needsRestore) {
      context.bridgeClient().restoreSession(sessionId, record.entryPointFqcn());
      bindLocally(sessionId, key);
    }
    return context;
  }

  private void bindLocally(String sessionId, ContextKey key) {
    if (localSessions.putIfAbsent(sessionId, key) == null) {
      contextManager.sessionStarted(key);
    }
  }

  private static String invoke(
      GameBridgeClient client, ProcessInputRequest request, String stateJson) {
    return switch (request.getInputCase()) {
      case ACTION ->
          client.processAction(
              request.getSessionId(),
              stateJson,
              TeamsJson.encode(request.getAction().getPlayer()),
              request.getAction().getPayload().toStringUtf8());
      case PLAYER_JOINED ->
          client.processPlayerJoined(
              request.getSessionId(),
              stateJson,
              TeamsJson.encode(request.getPlayerJoined().getPlayer()));
      case PLAYER_LEFT ->
          client.processPlayerLeft(
              request.getSessionId(),
              stateJson,
              TeamsJson.encode(request.getPlayerLeft().getPlayer()),
              playerLeftReason(request.getPlayerLeft().getReason()));
      case INPUT_NOT_SET ->
          throw new GameHostStatusException(
              Status.INVALID_ARGUMENT.withDescription("ProcessInputRequest has no input set"));
    };
  }

  private static String playerLeftReason(PlayerLeftReason reason) {
    return switch (reason) {
      case PLAYER_LEFT_REASON_GRACE_PERIOD_EXPIRED -> "GRACE_PERIOD_EXPIRED";
      case PLAYER_LEFT_REASON_KICKED -> "KICKED";
      case PLAYER_LEFT_REASON_LEFT -> "LEFT";
      case PLAYER_LEFT_REASON_UNSPECIFIED, UNRECOGNIZED ->
          throw new GameHostStatusException(
              Status.INVALID_ARGUMENT.withDescription("PlayerLeftInput.reason is unspecified"));
    };
  }

  /**
   * CreateSession replays unconditionally as OK per §9.1; ProcessInput replays the recorded
   * outcome.
   */
  private static ProcessResult replay(
      String sessionId, long sequence, SessionRecord record, boolean forceOk) {
    ProcessResult.Builder result =
        ProcessResult.newBuilder().setSessionId(sessionId).setSequence(sequence);
    if (forceOk || record.lastOutcome() == SessionRecord.Outcome.OK) {
      result
          .setStatus(ProcessStatus.PROCESS_STATUS_OK)
          .addAllEffects(EnvelopeCodec.decodeEffectsArray(record.lastEffectsJson()));
    } else {
      result
          .setStatus(ProcessStatus.PROCESS_STATUS_FAILED)
          .setFailure(
              EnvelopeCodec.failure(
                  Objects.requireNonNull(record.lastFailureKind()),
                  Objects.requireNonNull(record.lastFailureMessage())));
    }
    return result.build();
  }

  private <T> T withLock(String sessionId, Supplier<T> action) {
    ReentrantLock lock = locks.computeIfAbsent(sessionId, k -> new ReentrantLock());
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }
}
