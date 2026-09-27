/**
 * The host shim (game-host-internals.md §3.1) — host-JVM side of the GDK Host boundary. Owns the
 * polyglot {@code Context} and drives the guest bridge's {@code GameEntrypoint} through a single
 * {@code Value} handle, exchanging only JSON strings, primitives and byte arrays. This package must
 * never import {@code io.turnbase.gamehost.bridge}; it addresses that package only by its FQCN, as
 * a plain string, so the two halves stay separable across the host/guest boundary.
 */
@NullMarked
package io.turnbase.gamehost.shim;

import org.jspecify.annotations.NullMarked;
