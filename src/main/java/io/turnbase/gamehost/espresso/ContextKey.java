package io.turnbase.gamehost.espresso;

/** One Espresso {@code Context} per {@code (gameId, version)} (FR-CTX-001, §6.1). */
public record ContextKey(String gameId, String version) {}
