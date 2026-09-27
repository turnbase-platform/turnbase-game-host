package io.turnbase.gamehost.bridge;

import io.turnbase.gdk.context.PlayerLeftContext;
import io.turnbase.gdk.context.PlayerLeftReason;
import io.turnbase.gdk.model.Player;

record PlayerLeftContextImpl(Player player, PlayerLeftReason reason) implements PlayerLeftContext {}
