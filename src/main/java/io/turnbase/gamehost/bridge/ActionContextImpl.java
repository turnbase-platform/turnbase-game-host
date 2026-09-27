package io.turnbase.gamehost.bridge;

import io.turnbase.gdk.context.ActionContext;
import io.turnbase.gdk.context.ActionDecodeException;
import io.turnbase.gdk.model.Player;
import io.turnbase.serialization.GdkSerialization;
import io.turnbase.serialization.SerializationException;

/**
 * Decodes the action payload lazily, on the game's own call to {@link #action(Class)} — the
 * developer, not the bridge, knows the target type (backend-gdk-api.md §4).
 */
final class ActionContextImpl implements ActionContext {

  private final Player player;
  private final String payloadJson;
  private final GdkSerialization serialization;

  ActionContextImpl(Player player, String payloadJson, GdkSerialization serialization) {
    this.player = player;
    this.payloadJson = payloadJson;
    this.serialization = serialization;
  }

  @Override
  public Player player() {
    return player;
  }

  @Override
  public <A> A action(Class<A> type) {
    try {
      return serialization.readAction(payloadJson, type);
    } catch (SerializationException e) {
      throw new ActionDecodeException("cannot decode action as " + type.getName(), e);
    }
  }
}
