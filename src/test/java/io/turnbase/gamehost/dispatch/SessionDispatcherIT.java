package io.turnbase.gamehost.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.turnbase.gamehost.espresso.ContextKey;
import io.turnbase.gamehost.espresso.EspressoContextFactory;
import io.turnbase.gamehost.espresso.GameContextManager;
import io.turnbase.grpc.gamehost.v1.ActionInput;
import io.turnbase.grpc.gamehost.v1.CreateSessionRequest;
import io.turnbase.grpc.gamehost.v1.PlayerRef;
import io.turnbase.grpc.gamehost.v1.ProcessInputRequest;
import io.turnbase.grpc.gamehost.v1.ProcessResult;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import io.turnbase.grpc.gamehost.v1.Team;
import io.turnbase.grpc.gamehost.v1.TerminateSessionRequest;
import io.turnbase.grpc.gamehost.v1.TerminateSessionResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Drives {@link SessionDispatcher} end to end against a real Espresso context (the {@code
 * fixture.CountingGame} guest game) and a real Redis (conventions.md §3: Testcontainers, never
 * Docker Compose). Covers IMP-M3-04's Done-when clause — {@code TerminateSession} idempotency and
 * {@code NOT_FOUND} scoped to {@code ProcessInput}/{@code Resync} only — plus the surrounding
 * dispatch flow that makes that clause meaningful (game-host-internals.md §9.1/§9.3).
 *
 * <p>{@code sdkJarPath} is set to this JVM's own test classpath, same reasoning as {@code
 * GameBridgeClientEspressoTest}: the shaded {@code guest-bridge} jar is a {@code prepare-package}
 * artifact of this module and does not exist when tests run, but the test classpath already carries
 * the {@code io.turnbase.gamehost.bridge} classes, the SDK, Jackson and the fixture game.
 *
 * <p>The context manager is shared (static) across every test method rather than rebuilt per
 * method: every method targets the same {@code ContextKey}, so a fresh manager per method would
 * just mean one more real Espresso context, and the suite already runs several other
 * Espresso-backed test classes in the same forked JVM.
 */
@Testcontainers
class SessionDispatcherIT {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static final String ENTRY_POINT_FQCN = "io.turnbase.gamehost.bridge.fixture.CountingGame";

  private static final GameContextManager contextManager =
      new GameContextManager(
          new EspressoContextFactory(
              System.getProperty("java.home"), System.getProperty("java.class.path")),
          Duration.ofMinutes(5),
          0.95);

  /** Set by a test to hold a handler inside the session lock: resolving the artifact blocks. */
  private static volatile @Nullable CountDownLatch resolverGate;

  private static final CountDownLatch RESOLVER_ENTERED = new CountDownLatch(1);

  private static final GameArtifactResolver artifactResolver =
      (gameId, version) -> {
        CountDownLatch gate = resolverGate;
        if (gate != null) {
          RESOLVER_ENTERED.countDown();
          try {
            gate.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        return Path.of(".");
      };

  private static final ContextKey KEY = new ContextKey("tic-tac-toe", "1.0.0");

  @AfterAll
  static void closeContextManager() {
    contextManager.close();
  }

  private SessionDispatcher newDispatcher() {
    return newDispatcher(newRecordStore(), new TerminatedSessions());
  }

  private SessionRecordStore newRecordStore() {
    LettuceConnectionFactory connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
    return new SessionRecordStore(template);
  }

  private SessionDispatcher newDispatcher(SessionRecordStore store, TerminatedSessions terminated) {
    return new SessionDispatcher(contextManager, artifactResolver, store, terminated);
  }

  private static TerminateSessionRequest terminateRequest(String sessionId) {
    return TerminateSessionRequest.newBuilder().setSessionId(sessionId).build();
  }

  private static int guestSessions() {
    return contextManager.find(KEY).orElseThrow().bridgeClient().liveSessionCount();
  }

  private static void assertNotFound(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(GameHostStatusException.class)
        .extracting(e -> ((GameHostStatusException) e).status().getCode())
        .isEqualTo(Status.Code.NOT_FOUND);
  }

  private static CreateSessionRequest createRequest(String sessionId) {
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    PlayerRef player =
        PlayerRef.newBuilder()
            .setPlayerId(playerId.toString())
            .setDisplayName("Alice")
            .setTeamId(teamId.toString())
            .build();
    Team team =
        Team.newBuilder().setTeamId(teamId.toString()).setName("Team A").addPlayers(player).build();
    return CreateSessionRequest.newBuilder()
        .setSessionId(sessionId)
        .setContext(
            io.turnbase.grpc.gamehost.v1.ContextKey.newBuilder()
                .setGameId("tic-tac-toe")
                .setGameVersion("1.0.0"))
        .addTeams(team)
        .setParameters(ByteString.copyFromUtf8("{\"start\":0}"))
        .setEntryPointFqcn(ENTRY_POINT_FQCN)
        .build();
  }

  private static ProcessInputRequest actionRequest(String sessionId, long sequence, int by) {
    PlayerRef player =
        PlayerRef.newBuilder()
            .setPlayerId(UUID.randomUUID().toString())
            .setDisplayName("Alice")
            .setTeamId(UUID.randomUUID().toString())
            .build();
    return ProcessInputRequest.newBuilder()
        .setSessionId(sessionId)
        .setSequence(sequence)
        .setAction(
            ActionInput.newBuilder()
                .setPlayer(player)
                .setPayload(ByteString.copyFromUtf8("{\"by\":" + by + "}")))
        .build();
  }

  @Test
  void createSessionRunsOnInitAndPersistsTheRecord() {
    SessionDispatcher dispatcher = newDispatcher();
    String sessionId = UUID.randomUUID().toString();

    ProcessResult result = dispatcher.createSession(createRequest(sessionId));

    assertThat(result.getStatus()).isEqualTo(ProcessStatus.PROCESS_STATUS_OK);
    assertThat(result.getSequence()).isZero();
    assertThat(result.getEffectsList()).anyMatch(e -> e.hasLog());
  }

  @Test
  void createSessionIsIdempotentOnRetry() {
    SessionDispatcher dispatcher = newDispatcher();
    String sessionId = UUID.randomUUID().toString();

    ProcessResult first = dispatcher.createSession(createRequest(sessionId));
    ProcessResult second = dispatcher.createSession(createRequest(sessionId));

    assertThat(second.getStatus()).isEqualTo(ProcessStatus.PROCESS_STATUS_OK);
    assertThat(second.getEffectsList()).isEqualTo(first.getEffectsList());
  }

  @Test
  void processInputAdvancesStateAndReplayingASequenceDoesNotReapplyIt() {
    SessionDispatcher dispatcher = newDispatcher();
    String sessionId = UUID.randomUUID().toString();
    dispatcher.createSession(createRequest(sessionId));

    ProcessResult firstApply = dispatcher.processInput(actionRequest(sessionId, 1, 3));
    ProcessResult replay = dispatcher.processInput(actionRequest(sessionId, 1, 3));
    ProcessResult secondApply = dispatcher.processInput(actionRequest(sessionId, 2, 3));

    assertThat(firstApply.getStatus()).isEqualTo(ProcessStatus.PROCESS_STATUS_OK);
    assertThat(replay.getEffectsList()).isEqualTo(firstApply.getEffectsList());
    assertThat(secondApply.getStatus()).isEqualTo(ProcessStatus.PROCESS_STATUS_OK);
    // by:3 applied twice (sequences 1 and 2, not the sequence-1 replay) -> count reaches 6, not 10:
    // still a SEND_TO_PLAYER effect rather than the GAME_OVER the fixture emits at count >= 10.
    assertThat(secondApply.getEffectsList()).anyMatch(e -> e.hasSendToPlayer());
  }

  @Test
  void processInputForAnUnknownSessionIsNotFound() {
    SessionDispatcher dispatcher = newDispatcher();

    assertThatThrownBy(
            () -> dispatcher.processInput(actionRequest(UUID.randomUUID().toString(), 1, 3)))
        .isInstanceOf(GameHostStatusException.class)
        .extracting(e -> ((GameHostStatusException) e).status().getCode())
        .isEqualTo(Status.Code.NOT_FOUND);
  }

  @Test
  void terminateSessionIsIdempotentForASessionThatWasNeverCreated() {
    SessionDispatcher dispatcher = newDispatcher();
    String sessionId = UUID.randomUUID().toString();

    TerminateSessionResponse response =
        dispatcher.terminateSession(
            TerminateSessionRequest.newBuilder().setSessionId(sessionId).build());

    assertThat(response.getSessionId()).isEqualTo(sessionId);
  }

  @Test
  void terminateSessionCalledTwiceOnALiveSessionIsIdempotent() {
    SessionDispatcher dispatcher = newDispatcher();
    String sessionId = UUID.randomUUID().toString();
    dispatcher.createSession(createRequest(sessionId));

    TerminateSessionResponse first =
        dispatcher.terminateSession(
            TerminateSessionRequest.newBuilder().setSessionId(sessionId).build());
    TerminateSessionResponse second =
        dispatcher.terminateSession(
            TerminateSessionRequest.newBuilder().setSessionId(sessionId).build());

    assertThat(first.getSessionId()).isEqualTo(sessionId);
    assertThat(second.getSessionId()).isEqualTo(sessionId);
  }

  @Test
  void processInputAfterTerminationIsNotFound() {
    SessionDispatcher dispatcher = newDispatcher();
    String sessionId = UUID.randomUUID().toString();
    dispatcher.createSession(createRequest(sessionId));
    dispatcher.terminateSession(
        TerminateSessionRequest.newBuilder().setSessionId(sessionId).build());

    assertThatThrownBy(() -> dispatcher.processInput(actionRequest(sessionId, 1, 3)))
        .isInstanceOf(GameHostStatusException.class)
        .extracting(e -> ((GameHostStatusException) e).status().getCode())
        .isEqualTo(Status.Code.NOT_FOUND);
  }

  @Test
  void aFreshDispatcherRecoversASessionFromRedisWithoutRerunningOnInit() {
    SessionDispatcher first = newDispatcher();
    String sessionId = UUID.randomUUID().toString();
    first.createSession(createRequest(sessionId));
    first.processInput(actionRequest(sessionId, 1, 3));

    // A second dispatcher instance, sharing only the context manager and Redis, simulates this
    // host's per-session tracking having been lost (§9.3): its own `localSessions` map is empty,
    // so the next input for this session must go through `restoreSession` rather than fail.
    SessionDispatcher recovered = newDispatcher();
    ProcessResult result = recovered.processInput(actionRequest(sessionId, 2, 3));

    assertThat(result.getStatus()).isEqualTo(ProcessStatus.PROCESS_STATUS_OK);
    assertThat(result.getEffectsList()).anyMatch(e -> e.hasSendToPlayer());
  }

  @Test
  void aThousandCreatedAndTerminatedSessionsLeaveNothingBehindExceptTheBoundedTerminatedSet() {
    TerminatedSessions terminated = new TerminatedSessions();
    SessionDispatcher dispatcher = newDispatcher(newRecordStore(), terminated);
    dispatcher.createSession(createRequest(UUID.randomUUID().toString()));
    int guestBefore = guestSessions();
    int activeBefore = contextManager.activeSessionCount();
    int trackedBefore = dispatcher.trackedSessionCount();
    int terminatedBefore = terminated.size();

    for (int i = 0; i < 1_000; i++) {
      String sessionId = UUID.randomUUID().toString();
      dispatcher.createSession(createRequest(sessionId));
      dispatcher.processInput(actionRequest(sessionId, 1, 1));
      dispatcher.terminateSession(terminateRequest(sessionId));
    }

    assertThat(dispatcher.trackedSessionCount()).isEqualTo(trackedBefore);
    assertThat(contextManager.activeSessionCount()).isEqualTo(activeBefore);
    assertThat(guestSessions()).isEqualTo(guestBefore);
    assertThat(terminated.size() - terminatedBefore).isEqualTo(1_000);
    assertThat(terminated.size()).isLessThanOrEqualTo(TerminatedSessions.MAX_ENTRIES);
  }

  @Test
  void aLateInputForATerminatedSessionIsRejectedInsideTheTtlEvenIfItsRecordReappears() {
    AtomicLong nanos = new AtomicLong();
    SessionRecordStore store = newRecordStore();
    SessionDispatcher dispatcher =
        newDispatcher(store, new TerminatedSessions(TerminatedSessions.TTL, 10, nanos::get));
    String sessionId = UUID.randomUUID().toString();
    dispatcher.createSession(createRequest(sessionId));
    SessionRecord record = store.find(sessionId).orElseThrow();
    dispatcher.terminateSession(terminateRequest(sessionId));
    // An in-flight save on another host could put the record back after the delete.
    store.save(sessionId, record);

    nanos.set(TerminatedSessions.TTL.toNanos() - 1);
    assertNotFound(() -> dispatcher.processInput(actionRequest(sessionId, 1, 3)));

    nanos.set(TerminatedSessions.TTL.toNanos());
    assertThat(dispatcher.processInput(actionRequest(sessionId, 1, 3)).getStatus())
        .isEqualTo(ProcessStatus.PROCESS_STATUS_OK);
    dispatcher.terminateSession(terminateRequest(sessionId));
  }

  @Test
  void terminatingWhileAHandlerHoldsTheLockDisposesTheGuestInstanceWhenItReleases()
      throws Exception {
    SessionDispatcher dispatcher = newDispatcher();
    String sessionId = UUID.randomUUID().toString();
    dispatcher.createSession(createRequest(sessionId));
    int guestWithSession = guestSessions();

    CountDownLatch gate = new CountDownLatch(1);
    resolverGate = gate;
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<ProcessResult> inFlight =
          pool.submit(() -> dispatcher.processInput(actionRequest(sessionId, 1, 3)));
      assertThat(RESOLVER_ENTERED.await(30, TimeUnit.SECONDS)).isTrue();

      // Returns at once although the handler holds the session lock, and leaves the guest alone.
      dispatcher.terminateSession(terminateRequest(sessionId));
      assertThat(guestSessions()).isEqualTo(guestWithSession);

      resolverGate = null;
      gate.countDown();
      assertThat(inFlight.get(30, TimeUnit.SECONDS).getStatus())
          .isEqualTo(ProcessStatus.PROCESS_STATUS_OK);
    } finally {
      resolverGate = null;
      gate.countDown();
      pool.shutdownNow();
    }

    assertThat(guestSessions()).isEqualTo(guestWithSession - 1);
    assertThat(dispatcher.trackedSessionCount()).isZero();
    assertThat(newRecordStore().find(sessionId)).isEmpty();
  }
}
