package io.turnbase.gamehost.bridge;

import io.turnbase.gdk.GameState;
import io.turnbase.gdk.TurnBaseGame;
import io.turnbase.gdk.context.PlayerLeftReason;
import io.turnbase.gdk.context.ViewerRole;
import io.turnbase.gdk.effect.BroadcastEffect;
import io.turnbase.gdk.effect.Effect;
import io.turnbase.gdk.effect.GameOverEffect;
import io.turnbase.gdk.effect.LogEffect;
import io.turnbase.gdk.effect.SendToPlayerEffect;
import io.turnbase.gdk.effect.SendToTeamEffect;
import io.turnbase.gdk.model.GameParameters;
import io.turnbase.gdk.model.Player;
import io.turnbase.gdk.model.Team;
import io.turnbase.gdk.result.ActionResult;
import io.turnbase.gdk.result.InitResult;
import io.turnbase.gdk.result.PlayerJoinedResult;
import io.turnbase.gdk.result.PlayerLeftResult;
import io.turnbase.gdk.result.ViewResult;
import io.turnbase.serialization.GdkSerialization;
import io.turnbase.serialization.PayloadTooLargeException;
import io.turnbase.serialization.SerializationException;
import io.turnbase.serialization.SerializationLimits;
import io.turnbase.serialization.StateTooLargeException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The invocation entrypoint (game-host-internals.md §3.3) — the single class the host shim obtains
 * a {@code Value} handle to and drives with String/primitive arguments only. One instance lives per
 * Espresso context (one per {@code ContextKey}, §6.1); it tracks one {@code TurnBaseGame} instance
 * per live session (§6.2), instantiated at {@link #createSession} and discarded at {@link
 * #disposeSession}.
 */
public final class GameEntrypoint {

  private final GdkSerialization serialization = new GdkSerialization();
  private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

  /** One live session's game instance and the concrete state type it deserializes into. */
  private record Session(TurnBaseGame<GameState> game, Class<GameState> stateType) {}

  /**
   * @throws CodeLoadException if {@code entryPointFqcn} cannot be classloaded or does not implement
   *     {@code TurnBaseGame} (FR-LOAD-005) — crosses the boundary as a thrown exception, not folded
   *     into the returned envelope (see {@link CodeLoadException})
   */
  public String createSession(
      String sessionId, String entryPointFqcn, String teamsJson, String parametersJson) {
    Session session = loadEntryClass(entryPointFqcn);

    List<Team> teams = PlatformJson.decodeTeams(teamsJson);
    GameParameters parameters =
        new GameParametersImpl(PlatformJson.parseParameters(parametersJson));
    long seed = deriveSeed(sessionId);
    InitContextImpl ctx = new InitContextImpl(teams, parameters, seed);

    InitResult<GameState> result;
    try {
      result = session.game().onInit(ctx);
    } catch (RuntimeException e) {
      return PlatformJson.invocationFailure("HANDLER_EXCEPTION", describe(e));
    }
    if (result == null || result.state() == null || result.effects() == null) {
      return PlatformJson.invocationFailure(
          "MALFORMED_OUTPUT", "onInit returned a null result/state/effects");
    }

    sessions.put(sessionId, session);
    return buildInvocationEnvelope(result.state(), result.effects());
  }

  public String processAction(
      String sessionId, String stateJson, String playerJson, String payloadJson) {
    Session session = requireSession(sessionId);
    GameState state = serialization.readState(stateJson, session.stateType());
    Player player = PlatformJson.decodePlayer(playerJson);
    ActionContextImpl ctx = new ActionContextImpl(player, payloadJson, serialization);

    ActionResult<GameState> result;
    try {
      result = session.game().onAction(state, ctx);
    } catch (RuntimeException e) {
      return PlatformJson.invocationFailure("HANDLER_EXCEPTION", describe(e));
    }
    if (result == null || result.state() == null || result.effects() == null) {
      return PlatformJson.invocationFailure(
          "MALFORMED_OUTPUT", "onAction returned a null result/state/effects");
    }
    return buildInvocationEnvelope(result.state(), result.effects());
  }

  public String processPlayerJoined(String sessionId, String stateJson, String playerJson) {
    Session session = requireSession(sessionId);
    GameState state = serialization.readState(stateJson, session.stateType());
    Player player = PlatformJson.decodePlayer(playerJson);
    PlayerJoinedContextImpl ctx = new PlayerJoinedContextImpl(player);

    PlayerJoinedResult<GameState> result;
    try {
      result = session.game().onPlayerJoined(state, ctx);
    } catch (RuntimeException e) {
      return PlatformJson.invocationFailure("HANDLER_EXCEPTION", describe(e));
    }
    if (result == null || result.state() == null || result.effects() == null) {
      return PlatformJson.invocationFailure(
          "MALFORMED_OUTPUT", "onPlayerJoined returned a null result/state/effects");
    }
    return buildInvocationEnvelope(result.state(), result.effects());
  }

  public String processPlayerLeft(
      String sessionId, String stateJson, String playerJson, String reason) {
    Session session = requireSession(sessionId);
    GameState state = serialization.readState(stateJson, session.stateType());
    Player player = PlatformJson.decodePlayer(playerJson);
    PlayerLeftContextImpl ctx = new PlayerLeftContextImpl(player, PlayerLeftReason.valueOf(reason));

    PlayerLeftResult<GameState> result;
    try {
      result = session.game().onPlayerLeft(state, ctx);
    } catch (RuntimeException e) {
      return PlatformJson.invocationFailure("HANDLER_EXCEPTION", describe(e));
    }
    if (result == null || result.state() == null || result.effects() == null) {
      return PlatformJson.invocationFailure(
          "MALFORMED_OUTPUT", "onPlayerLeft returned a null result/state/effects");
    }
    return buildInvocationEnvelope(result.state(), result.effects());
  }

  /**
   * Read-only (backend-gdk-api.md §3, §9) — a failure here fails only this viewer's attach, never
   * folded into a session-terminating failure kind beyond {@code HANDLER_EXCEPTION}/{@code
   * MALFORMED_OUTPUT}.
   */
  public String resync(String sessionId, String stateJson, String viewerJson, String role) {
    Session session = requireSession(sessionId);
    GameState state = serialization.readState(stateJson, session.stateType());
    ViewerRole viewerRole = ViewerRole.valueOf(role);
    Optional<Player> viewer =
        (viewerJson == null || viewerJson.isBlank() || viewerJson.equals("null"))
            ? Optional.empty()
            : Optional.of(PlatformJson.decodePlayer(viewerJson));
    ResyncContextImpl ctx = new ResyncContextImpl(viewerRole, viewer);

    ViewResult result;
    try {
      result = session.game().onResync(state, ctx);
    } catch (RuntimeException e) {
      return PlatformJson.viewFailure("HANDLER_EXCEPTION", describe(e));
    }
    if (result == null || result.view() == null) {
      return PlatformJson.viewFailure("MALFORMED_OUTPUT", "onResync returned a null result/view");
    }
    String viewJson;
    try {
      viewJson =
          serialization.writePayload(result.view(), SerializationLimits.DEFAULT_MAX_PAYLOAD_BYTES);
    } catch (PayloadTooLargeException | SerializationException e) {
      return PlatformJson.viewFailure("MALFORMED_OUTPUT", describe(e));
    }
    return PlatformJson.viewSuccess(viewJson);
  }

  public void disposeSession(String sessionId) {
    sessions.remove(sessionId);
  }

  /** How many game instances this context currently holds — one per live session (§6.2). */
  public int liveSessionCount() {
    return sessions.size();
  }

  /**
   * Crash recovery (game-host-internals.md §3.3/§9.3): reconstructs the guest-side game instance
   * for a session whose Redis record survived a Game Host crash, without re-running {@code onInit}.
   * The caller supplies the persisted state on the next {@code process*}/{@code resync} call, same
   * as any other invocation.
   *
   * @throws CodeLoadException under the same conditions as {@link #createSession}
   */
  public void restoreSession(String sessionId, String entryPointFqcn) {
    sessions.put(sessionId, loadEntryClass(entryPointFqcn));
  }

  private Session requireSession(String sessionId) {
    Session session = sessions.get(sessionId);
    if (session == null) {
      throw new IllegalStateException("no live session: " + sessionId);
    }
    return session;
  }

  private String buildInvocationEnvelope(GameState state, List<? extends Effect> effects) {
    String stateJson;
    try {
      stateJson = serialization.writeState(state, SerializationLimits.DEFAULT_MAX_STATE_BYTES);
    } catch (StateTooLargeException e) {
      return PlatformJson.invocationFailure("STATE_TOO_LARGE", describe(e));
    } catch (SerializationException e) {
      return PlatformJson.invocationFailure("MALFORMED_OUTPUT", describe(e));
    }

    PlatformJson.EnvelopeBuilder builder = PlatformJson.invocationSuccess(stateJson);
    try {
      for (Effect effect : effects) {
        switch (effect) {
          case BroadcastEffect e -> builder.broadcast(writeEffectPayload(e.payload()));
          case SendToPlayerEffect e ->
              builder.sendToPlayer(e.playerId(), writeEffectPayload(e.payload()));
          case SendToTeamEffect e ->
              builder.sendToTeam(e.teamId(), writeEffectPayload(e.payload()));
          case LogEffect e -> builder.log(e.text());
          case GameOverEffect e ->
              builder.gameOver(e.outcome(), writeEffectPayload(e.resultsPayload()));
        }
      }
    } catch (PayloadTooLargeException | SerializationException e) {
      return PlatformJson.invocationFailure("MALFORMED_OUTPUT", describe(e));
    }
    return builder.build();
  }

  private String writeEffectPayload(Object payload) {
    return serialization.writePayload(payload, SerializationLimits.DEFAULT_MAX_PAYLOAD_BYTES);
  }

  private Session loadEntryClass(String entryPointFqcn) {
    Class<?> entryClass;
    try {
      entryClass = Class.forName(entryPointFqcn);
    } catch (ClassNotFoundException e) {
      throw new CodeLoadException("entry class not found: " + entryPointFqcn, e);
    }
    if (!TurnBaseGame.class.isAssignableFrom(entryClass)) {
      throw new CodeLoadException(entryPointFqcn + " does not implement TurnBaseGame");
    }
    @SuppressWarnings("unchecked")
    Class<GameState> stateType = (Class<GameState>) resolveStateType(entryClass);

    Object instance;
    try {
      instance = entryClass.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new CodeLoadException("failed to instantiate " + entryPointFqcn, e);
    }
    @SuppressWarnings("unchecked")
    TurnBaseGame<GameState> game = (TurnBaseGame<GameState>) instance;
    return new Session(game, stateType);
  }

  /** Resolves {@code S} from {@code class X implements TurnBaseGame<S>} by reflection. */
  private static Class<?> resolveStateType(Class<?> gameClass) {
    for (Class<?> current = gameClass;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Type iface : current.getGenericInterfaces()) {
        if (iface instanceof ParameterizedType parameterized
            && parameterized.getRawType() == TurnBaseGame.class
            && parameterized.getActualTypeArguments()[0] instanceof Class<?> stateType) {
          return stateType;
        }
      }
    }
    throw new CodeLoadException("cannot determine the game state type of " + gameClass.getName());
  }

  private static long deriveSeed(String sessionId) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(sessionId.getBytes(StandardCharsets.UTF_8));
      long seed = 0;
      for (int i = hash.length - 8; i < hash.length; i++) {
        seed = (seed << 8) | (hash[i] & 0xFFL);
      }
      return seed;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
    }
  }

  private static String describe(Exception e) {
    String message = e.getMessage();
    return message == null ? e.getClass().getName() : message;
  }
}
