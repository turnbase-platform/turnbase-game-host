package io.turnbase.gamehost.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.turnbase.gamehost.bridge.fixture.CountState;
import io.turnbase.gdk.TurnBaseGame;
import io.turnbase.gdk.context.ActionContext;
import io.turnbase.gdk.context.InitContext;
import io.turnbase.gdk.context.PlayerLeftContext;
import io.turnbase.gdk.context.ResyncContext;
import io.turnbase.gdk.result.ActionResult;
import io.turnbase.gdk.result.InitResult;
import io.turnbase.gdk.result.PlayerLeftResult;
import io.turnbase.gdk.result.ViewResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link GameEntrypoint} directly on the host JVM — no Espresso context — to exercise the
 * branches {@code GameBridgeClientEspressoTest} does not reach (code-load failures, handler
 * exceptions, malformed hook output). This is exactly the same class production runs inside
 * Espresso; nothing here is Espresso-specific, so plain host-side JUnit is a faithful way to reach
 * these paths for coverage without paying an Espresso context's cost per branch.
 */
class GameEntrypointTest {

  private static final String COUNTING_GAME_FQCN =
      "io.turnbase.gamehost.bridge.fixture.CountingGame";

  private final GameEntrypoint entrypoint = new GameEntrypoint();

  @Test
  void createSessionRunsOnInitAndReturnsAnOkInvocationEnvelope() {
    String sessionId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();

    String envelope =
        entrypoint.createSession(
            sessionId, COUNTING_GAME_FQCN, teamsJson(teamId, playerId), "{\"start\":0}");

    assertThat(envelope).contains("\"status\":\"OK\"");
    assertThat(envelope).contains("\"stateJson\":\"{\\\"count\\\":0}\"");
  }

  @Test
  void createSessionRejectsAnUnknownEntryClass() {
    assertThatThrownBy(
            () ->
                entrypoint.createSession(
                    UUID.randomUUID().toString(),
                    "io.turnbase.gamehost.bridge.NoSuchGame",
                    "[]",
                    "{}"))
        .isInstanceOf(CodeLoadException.class)
        .hasMessageContaining("entry class not found");
  }

  @Test
  void createSessionRejectsAClassThatDoesNotImplementTurnBaseGame() {
    assertThatThrownBy(
            () ->
                entrypoint.createSession(
                    UUID.randomUUID().toString(), "java.lang.String", "[]", "{}"))
        .isInstanceOf(CodeLoadException.class)
        .hasMessageContaining("does not implement TurnBaseGame");
  }

  @Test
  void createSessionFoldsAnOnInitExceptionIntoHandlerException() {
    String envelope =
        entrypoint.createSession(
            UUID.randomUUID().toString(), ThrowingOnInitGame.class.getName(), "[]", "{}");

    assertThat(envelope).contains("\"status\":\"FAILED\"");
    assertThat(envelope).contains("\"failureKind\":\"HANDLER_EXCEPTION\"");
    assertThat(envelope).contains("init boom");
  }

  @Test
  void createSessionFoldsANullOnInitResultIntoMalformedOutput() {
    String envelope =
        entrypoint.createSession(
            UUID.randomUUID().toString(), NullOnInitGame.class.getName(), "[]", "{}");

    assertThat(envelope).contains("\"status\":\"FAILED\"");
    assertThat(envelope).contains("\"failureKind\":\"MALFORMED_OUTPUT\"");
  }

  @Test
  void processActionAdvancesStateAndFansOutASendToPlayerEffect() {
    String sessionId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    entrypoint.createSession(
        sessionId, COUNTING_GAME_FQCN, teamsJson(teamId, playerId), "{\"start\":0}");

    String envelope =
        entrypoint.processAction(
            sessionId, "{\"count\":0}", playerJson(playerId, teamId), "{\"by\":3}");

    assertThat(envelope).contains("\"status\":\"OK\"");
    assertThat(envelope).contains("\"stateJson\":\"{\\\"count\\\":3}\"");
    assertThat(envelope).contains("\"type\":\"SEND_TO_PLAYER\"");
  }

  @Test
  void processActionReachingTargetEmitsAGameOverEffect() {
    String sessionId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    entrypoint.createSession(
        sessionId, COUNTING_GAME_FQCN, teamsJson(teamId, playerId), "{\"start\":0}");

    String envelope =
        entrypoint.processAction(
            sessionId, "{\"count\":0}", playerJson(playerId, teamId), "{\"by\":10}");

    assertThat(envelope).contains("\"type\":\"GAME_OVER\"");
    assertThat(envelope).contains("\"draw\":false");
  }

  @Test
  void processActionOnAnUnknownSessionThrows() {
    assertThatThrownBy(() -> entrypoint.processAction("no-such-session", "{}", "{}", "{}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no live session");
  }

  @Test
  void processActionWithAMalformedPayloadFoldsIntoHandlerException() {
    String sessionId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    entrypoint.createSession(
        sessionId, COUNTING_GAME_FQCN, teamsJson(teamId, playerId), "{\"start\":0}");

    String envelope =
        entrypoint.processAction(
            sessionId,
            "{\"count\":0}",
            playerJson(playerId, teamId),
            "\"not an Increment object\"");

    assertThat(envelope).contains("\"status\":\"FAILED\"");
    assertThat(envelope).contains("\"failureKind\":\"HANDLER_EXCEPTION\"");
  }

  @Test
  void processActionWithANullHookResultFoldsIntoMalformedOutput() {
    String sessionId = UUID.randomUUID().toString();
    entrypoint.createSession(sessionId, NullOnActionGame.class.getName(), "[]", "{}");

    String envelope = entrypoint.processAction(sessionId, "{\"count\":0}", playerJson(), "{}");

    assertThat(envelope).contains("\"status\":\"FAILED\"");
    assertThat(envelope).contains("\"failureKind\":\"MALFORMED_OUTPUT\"");
  }

  @Test
  void processPlayerJoinedUsesTheDefaultNoOpHook() {
    String sessionId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    entrypoint.createSession(
        sessionId, COUNTING_GAME_FQCN, teamsJson(teamId, playerId), "{\"start\":0}");

    String envelope =
        entrypoint.processPlayerJoined(sessionId, "{\"count\":5}", playerJson(playerId, teamId));

    assertThat(envelope).contains("\"status\":\"OK\"");
    assertThat(envelope).contains("\"stateJson\":\"{\\\"count\\\":5}\"");
  }

  @Test
  void processPlayerLeftReturnsTheUnchangedState() {
    String sessionId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    entrypoint.createSession(
        sessionId, COUNTING_GAME_FQCN, teamsJson(teamId, playerId), "{\"start\":0}");

    String envelope =
        entrypoint.processPlayerLeft(
            sessionId, "{\"count\":2}", playerJson(playerId, teamId), "GRACE_PERIOD_EXPIRED");

    assertThat(envelope).contains("\"status\":\"OK\"");
    assertThat(envelope).contains("\"stateJson\":\"{\\\"count\\\":2}\"");
  }

  @Test
  void resyncReturnsAViewForAPlayerAndForASpectator() {
    String sessionId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    entrypoint.createSession(
        sessionId, COUNTING_GAME_FQCN, teamsJson(teamId, playerId), "{\"start\":0}");

    String forPlayer =
        entrypoint.resync(sessionId, "{\"count\":7}", playerJson(playerId, teamId), "PLAYER");
    String forSpectator = entrypoint.resync(sessionId, "{\"count\":7}", "null", "SPECTATOR");

    assertThat(forPlayer).contains("\"viewJson\":\"{\\\"count\\\":7}\"");
    assertThat(forSpectator).contains("\"viewJson\":\"{\\\"count\\\":7}\"");
  }

  @Test
  void resyncFoldsAnOnResyncExceptionIntoHandlerException() {
    String sessionId = UUID.randomUUID().toString();
    entrypoint.createSession(sessionId, ThrowingOnResyncGame.class.getName(), "[]", "{}");

    String envelope = entrypoint.resync(sessionId, "{\"count\":0}", "null", "SPECTATOR");

    assertThat(envelope).contains("\"status\":\"FAILED\"");
    assertThat(envelope).contains("\"failureKind\":\"HANDLER_EXCEPTION\"");
  }

  @Test
  void resyncFoldsANullViewResultIntoMalformedOutput() {
    String sessionId = UUID.randomUUID().toString();
    entrypoint.createSession(sessionId, NullOnResyncGame.class.getName(), "[]", "{}");

    String envelope = entrypoint.resync(sessionId, "{\"count\":0}", "null", "SPECTATOR");

    assertThat(envelope).contains("\"status\":\"FAILED\"");
    assertThat(envelope).contains("\"failureKind\":\"MALFORMED_OUTPUT\"");
  }

  @Test
  void disposeSessionEndsTheSessionsLifecycle() {
    String sessionId = UUID.randomUUID().toString();
    entrypoint.createSession(sessionId, COUNTING_GAME_FQCN, "[]", "{\"start\":0}");

    entrypoint.disposeSession(sessionId);

    assertThatThrownBy(() -> entrypoint.processAction(sessionId, "{\"count\":0}", "{}", "{}"))
        .isInstanceOf(IllegalStateException.class);
  }

  private static String teamsJson(UUID teamId, UUID playerId) {
    return "[{\"teamId\":\""
        + teamId
        + "\",\"name\":\"Team A\",\"players\":["
        + playerJson(playerId, teamId)
        + "]}]";
  }

  private static String playerJson(UUID playerId, UUID teamId) {
    return "{\"playerId\":\""
        + playerId
        + "\",\"displayName\":\"Alice\",\"teamId\":\""
        + teamId
        + "\"}";
  }

  private static String playerJson() {
    return playerJson(UUID.randomUUID(), UUID.randomUUID());
  }

  /** Test fixture: fails at {@code onInit} to exercise the handler-exception fold. */
  public static final class ThrowingOnInitGame implements TurnBaseGame<CountState> {
    @Override
    public InitResult<CountState> onInit(InitContext ctx) {
      throw new IllegalStateException("init boom");
    }

    @Override
    public ActionResult<CountState> onAction(CountState state, ActionContext ctx) {
      return ActionResult.of(state);
    }

    @Override
    public PlayerLeftResult<CountState> onPlayerLeft(CountState state, PlayerLeftContext ctx) {
      return PlayerLeftResult.of(state);
    }

    @Override
    public ViewResult onResync(CountState state, ResyncContext ctx) {
      return ViewResult.of(state);
    }
  }

  /** Test fixture: returns a null {@code InitResult} to exercise the malformed-output fold. */
  public static final class NullOnInitGame implements TurnBaseGame<CountState> {
    @Override
    @SuppressWarnings("NullAway")
    public InitResult<CountState> onInit(InitContext ctx) {
      return null;
    }

    @Override
    public ActionResult<CountState> onAction(CountState state, ActionContext ctx) {
      return ActionResult.of(state);
    }

    @Override
    public PlayerLeftResult<CountState> onPlayerLeft(CountState state, PlayerLeftContext ctx) {
      return PlayerLeftResult.of(state);
    }

    @Override
    public ViewResult onResync(CountState state, ResyncContext ctx) {
      return ViewResult.of(state);
    }
  }

  /** Test fixture: returns a null {@code ActionResult} to exercise the malformed-output fold. */
  public static final class NullOnActionGame implements TurnBaseGame<CountState> {
    @Override
    public InitResult<CountState> onInit(InitContext ctx) {
      return InitResult.of(new CountState(0));
    }

    @Override
    @SuppressWarnings("NullAway")
    public ActionResult<CountState> onAction(CountState state, ActionContext ctx) {
      return null;
    }

    @Override
    public PlayerLeftResult<CountState> onPlayerLeft(CountState state, PlayerLeftContext ctx) {
      return PlayerLeftResult.of(state);
    }

    @Override
    public ViewResult onResync(CountState state, ResyncContext ctx) {
      return ViewResult.of(state);
    }
  }

  /** Test fixture: fails at {@code onResync} to exercise the view-side handler-exception fold. */
  public static final class ThrowingOnResyncGame implements TurnBaseGame<CountState> {
    @Override
    public InitResult<CountState> onInit(InitContext ctx) {
      return InitResult.of(new CountState(0));
    }

    @Override
    public ActionResult<CountState> onAction(CountState state, ActionContext ctx) {
      return ActionResult.of(state);
    }

    @Override
    public PlayerLeftResult<CountState> onPlayerLeft(CountState state, PlayerLeftContext ctx) {
      return PlayerLeftResult.of(state);
    }

    @Override
    public ViewResult onResync(CountState state, ResyncContext ctx) {
      throw new IllegalStateException("resync boom");
    }
  }

  /** Test fixture: returns a null {@code ViewResult} to exercise the view-side malformed fold. */
  public static final class NullOnResyncGame implements TurnBaseGame<CountState> {
    @Override
    public InitResult<CountState> onInit(InitContext ctx) {
      return InitResult.of(new CountState(0));
    }

    @Override
    public ActionResult<CountState> onAction(CountState state, ActionContext ctx) {
      return ActionResult.of(state);
    }

    @Override
    public PlayerLeftResult<CountState> onPlayerLeft(CountState state, PlayerLeftContext ctx) {
      return PlayerLeftResult.of(state);
    }

    @Override
    @SuppressWarnings("NullAway")
    public ViewResult onResync(CountState state, ResyncContext ctx) {
      return null;
    }
  }
}
