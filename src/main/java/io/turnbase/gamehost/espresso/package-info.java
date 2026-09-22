/**
 * Guest context lifecycle via Espresso (Java-on-Truffle), on GraalVM CE for JDK 21 —
 * game-host-internals.md §4 (isolation), §6 (context/session model). FR-CTX-004/005/007
 * (filesystem, network, per-context memory) are the container's job, not this JVM's — see
 * espresso-spike-results.md; this package covers only what the spike found Espresso itself
 * enforces.
 */
@NullMarked
package io.turnbase.gamehost.espresso;

import org.jspecify.annotations.NullMarked;
