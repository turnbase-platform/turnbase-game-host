package io.turnbase.gamehost.espresso;

import org.graalvm.polyglot.Context;

/**
 * Builds an Espresso ({@code "java"}) guest context on GraalVM CE for JDK 21.
 *
 * <p>{@code allowNativeAccess(true)} is not a relaxation — Espresso on CE cannot boot without it
 * (espresso-spike-results.md: {@code allowNativeAccess(false)} throws {@code EspressoError} before
 * a context exists at all). Real FS/network/heap isolation is the container's job, not this JVM's —
 * the spike found {@code IOAccess.NONE} and {@code sandbox.MaxHeapMemory} do not hold on CE. This
 * factory makes no isolation claims; IMP-M3-02 owns the real context lifecycle and the
 * container-layer controls that back it.
 *
 * @param guestJavaHome path to a real JDK 21 install for the guest — the Maven-bundled Espresso
 *     guest JDK is 17 and rejects Java-21 (class major 65) bytecode
 */
public record EspressoContextFactory(String guestJavaHome) {

  public Context create() {
    return Context.newBuilder("java")
        .allowNativeAccess(true)
        .option("java.JavaHome", guestJavaHome)
        .build();
  }
}
