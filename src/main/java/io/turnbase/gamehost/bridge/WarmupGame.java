package io.turnbase.gamehost.bridge;

import io.turnbase.gdk.GameState;
import io.turnbase.gdk.TurnBaseGame;
import io.turnbase.gdk.context.ActionContext;
import io.turnbase.gdk.context.InitContext;
import io.turnbase.gdk.context.PlayerLeftContext;
import io.turnbase.gdk.context.ResyncContext;
import io.turnbase.gdk.effect.Effects;
import io.turnbase.gdk.model.GameOutcome;
import io.turnbase.gdk.result.ActionResult;
import io.turnbase.gdk.result.InitResult;
import io.turnbase.gdk.result.PlayerLeftResult;
import io.turnbase.gdk.result.ViewResult;
import java.util.Map;

/**
 * The trivial game the Game Host drives through a synthetic session before it reports readiness
 * (IMP-M7-09), so the first real {@code START_GAME} does not pay the Truffle/Espresso warm-up. It
 * exercises every guest-boundary path a real session does — init, action (state and view
 * serialization, both a per-player and a game-over effect), resync — and is never reachable as a
 * catalog game.
 */
public final class WarmupGame implements TurnBaseGame<WarmupGame.State> {

  @Override
  public InitResult<State> onInit(InitContext ctx) {
    return InitResult.from(new State(0)).effect(Effects.log("warm-up")).build();
  }

  @Override
  public ActionResult<State> onAction(State state, ActionContext ctx) {
    Tick tick = ctx.action(Tick.class);
    State next = new State(state.count() + tick.by());
    if (next.count() >= 2) {
      GameOutcome outcome =
          GameOutcome.builder()
              .playerResult(ctx.player().id(), Map.of("count", String.valueOf(next.count())))
              .draw(false)
              .build();
      return ActionResult.from(next)
          .effect(Effects.gameOver(outcome, new View(next.count())))
          .build();
    }
    return ActionResult.from(next)
        .effect(Effects.sendToPlayer(ctx.player().id(), new View(next.count())))
        .build();
  }

  @Override
  public PlayerLeftResult<State> onPlayerLeft(State state, PlayerLeftContext ctx) {
    return PlayerLeftResult.of(state);
  }

  @Override
  public ViewResult onResync(State state, ResyncContext ctx) {
    return ViewResult.of(new View(state.count()));
  }

  /** The running count. */
  public record State(int count) implements GameState {}

  /** {@code onAction} payload. */
  public record Tick(int by) {}

  /** The view sent to players. */
  public record View(int count) {}
}
