/**
 * The guest bridge (game-host-internals.md §3.2/§3.6) — runs inside the Espresso guest context on
 * the shaded {@code guest-bridge} classpath, never on the host JVM. The host shim ({@code
 * io.turnbase.gamehost.shim}) must never import this package; the two communicate only through
 * {@link io.turnbase.gamehost.bridge.GameEntrypoint}'s String/primitive method signatures, invoked
 * across the polyglot boundary.
 */
@NullMarked
package io.turnbase.gamehost.bridge;

import org.jspecify.annotations.NullMarked;
