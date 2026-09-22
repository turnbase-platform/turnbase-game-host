/**
 * Game backend JAR fetch and cache (game-host-internals.md §7.1, FR-LOAD-001…004). {@link
 * io.turnbase.gamehost.artifact.ArtifactStore} is read-only here — the Game Host never writes to
 * the store, only the Platform Monolith does (secrets-and-configuration.md).
 */
@NullMarked
package io.turnbase.gamehost.artifact;

import org.jspecify.annotations.NullMarked;
