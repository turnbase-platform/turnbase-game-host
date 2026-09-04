/**
 * Guest context creation via Espresso (Java-on-Truffle), on GraalVM CE for JDK 21. The FR-CTX
 * isolation controls this package does *not* claim are the container's job, not this JVM's — see
 * espresso-spike-results.md. Real context lifecycle management is IMP-M3-02.
 */
@NullMarked
package io.turnbase.gamehost.espresso;

import org.jspecify.annotations.NullMarked;
