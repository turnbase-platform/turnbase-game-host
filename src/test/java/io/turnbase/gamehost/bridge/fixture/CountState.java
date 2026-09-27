package io.turnbase.gamehost.bridge.fixture;

import io.turnbase.gdk.GameState;

/** {@link CountingGame}'s state: the running count. */
public record CountState(int count) implements GameState {}
