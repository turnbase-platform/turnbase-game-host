/**
 * Dispatches the four {@code GameHostService} RPCs (game-host-internals.md §9) against the
 * context/bridge machinery ({@code io.turnbase.gamehost.espresso}/{@code .shim}) and the
 * Redis-backed {@code session:{sessionId}} record (§8.1/§13).
 */
@NullMarked
package io.turnbase.gamehost.dispatch;

import org.jspecify.annotations.NullMarked;
