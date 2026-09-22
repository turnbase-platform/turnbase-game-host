package io.turnbase.gamehost.espresso;

import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;

/**
 * Builds an Espresso ({@code "java"}) guest context on GraalVM CE for JDK 21, per the spike-locked
 * configuration in game-host-internals.md §4.2.
 *
 * <p>{@code allowNativeAccess(true)} is not a relaxation — Espresso on CE cannot boot without it
 * (espresso-spike-results.md: {@code allowNativeAccess(false)} throws {@code EspressoError} before
 * a context exists at all). Real FS/network/heap isolation is the container's job, not this JVM's —
 * the spike found {@code IOAccess.NONE} and {@code sandbox.MaxHeapMemory} do not hold on CE (§4's
 * table); everything set here is exactly what the spike found *does* hold
 * (FR-CTX-001/003/006/008/011).
 *
 * @param guestJavaHome path to a real JDK 21 install for the guest — the Maven-bundled Espresso
 *     guest JDK is 17 and rejects Java-21 (class major 65) bytecode
 * @param sdkJarPath the guest bridge jar carrying the GDK API classes (§4.2's {@code
 *     guestBridgeJar}) — a provisioned filesystem path, not a Maven dependency of this module
 */
public record EspressoContextFactory(String guestJavaHome, String sdkJarPath) {

  public Context create(Path gameArtifactPath) {
    return Context.newBuilder("java")
        .allowExperimentalOptions(true)
        .allowNativeAccess(true)
        .option("java.JavaHome", guestJavaHome)
        .option("java.ExitHost", "false")
        .option("java.Classpath", GuestClasspathBuilder.build(sdkJarPath, gameArtifactPath))
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLoading(false)
        .allowCreateProcess(false)
        .allowEnvironmentAccess(EnvironmentAccess.NONE)
        .build();
  }
}
