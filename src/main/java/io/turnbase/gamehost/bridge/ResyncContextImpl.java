package io.turnbase.gamehost.bridge;

import io.turnbase.gdk.context.ResyncContext;
import io.turnbase.gdk.context.ViewerRole;
import io.turnbase.gdk.model.Player;
import java.util.Optional;

record ResyncContextImpl(ViewerRole role, Optional<Player> viewer) implements ResyncContext {}
