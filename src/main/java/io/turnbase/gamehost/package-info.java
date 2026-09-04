/**
 * TurnBase Game Host — application root. Runs official Java game backends in an Espresso guest
 * context and speaks gRPC to the Platform Monolith. Spring Modulith does not apply here
 * (CLAUDE.md); this is one bounded service, not a feature-partitioned monolith.
 */
@NullMarked
package io.turnbase.gamehost;

import org.jspecify.annotations.NullMarked;
