package io.turnbase.gamehost.bridge;

import io.turnbase.gdk.context.InitContext;
import io.turnbase.gdk.model.GameParameters;
import io.turnbase.gdk.model.Team;
import java.util.List;

record InitContextImpl(List<Team> teams, GameParameters parameters, long seed)
    implements InitContext {}
