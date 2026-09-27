package io.turnbase.gamehost.bridge.fixture;

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
 * A minimal {@code TurnBaseGame} used only to drive {@code GameEntrypoint} through a real Espresso
 * context ({@code GameEntrypointEspressoTest}) — not itself a unit under test. Counts up from a
 * configured start; the acting player wins once the count reaches {@code target}.
 */
public final class CountingGame implements TurnBaseGame<CountState> {

  @Override
  public InitResult<CountState> onInit(InitContext ctx) {
    int start = ctx.parameters().getInt("start");
    return InitResult.from(new CountState(start))
        .effect(Effects.log("started at " + start))
        .build();
  }

  @Override
  public ActionResult<CountState> onAction(CountState state, ActionContext ctx) {
    Increment increment = ctx.action(Increment.class);
    CountState next = new CountState(state.count() + increment.by());
    int target = 10;
    if (next.count() >= target) {
      GameOutcome outcome =
          GameOutcome.builder()
              .playerResult(ctx.player().id(), Map.of("finalCount", String.valueOf(next.count())))
              .draw(false)
              .build();
      return ActionResult.from(next)
          .effect(Effects.log("**" + ctx.player().displayName() + "** reached the target"))
          .effect(Effects.gameOver(outcome, new CountView(next.count())))
          .build();
    }
    return ActionResult.from(next)
        .effect(Effects.sendToPlayer(ctx.player().id(), new CountView(next.count())))
        .build();
  }

  @Override
  public PlayerLeftResult<CountState> onPlayerLeft(CountState state, PlayerLeftContext ctx) {
    return PlayerLeftResult.of(state);
  }

  @Override
  public ViewResult onResync(CountState state, ResyncContext ctx) {
    return ViewResult.of(new CountView(state.count()));
  }

  /** {@code onAction} payload: increment the count by this much. */
  public record Increment(int by) {}

  /** FE-facing view of the current count. */
  public record CountView(int count) {}
}
