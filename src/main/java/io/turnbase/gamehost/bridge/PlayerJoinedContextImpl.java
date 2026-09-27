package io.turnbase.gamehost.bridge;

import io.turnbase.gdk.context.PlayerJoinedContext;
import io.turnbase.gdk.model.Player;

record PlayerJoinedContextImpl(Player player) implements PlayerJoinedContext {}
