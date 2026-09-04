/**
 * The {@code GameHostRegistryService} client — this service registers with, and heartbeats to, the
 * Platform Monolith (game-host-internals.md §11). The real registration/heartbeat/drain machinery
 * is IMP-M3-05; this package only proves the {@code Register} call runs (IMP-GATE-08).
 */
@NullMarked
package io.turnbase.gamehost.registration;

import org.jspecify.annotations.NullMarked;
