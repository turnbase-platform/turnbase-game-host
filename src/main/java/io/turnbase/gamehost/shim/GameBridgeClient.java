package io.turnbase.gamehost.shim;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * Drives one guest bridge {@code GameEntrypoint} instance (game-host-internals.md §3.1, §3.3)
 * through a single {@code Value} handle obtained at construction. Every method here takes and
 * returns only Strings — the host shim never touches game classes or rich objects.
 *
 * <p>Addresses the guest bridge only by its fully-qualified class name, as a plain string, so this
 * class has no compile-time dependency on {@code io.turnbase.gamehost.bridge} (a package that
 * exists only on the guest classpath, not this module's host-JVM classpath).
 *
 * <p>A code-load failure ({@code createSession} with a missing or malformed entry class,
 * FR-LOAD-005) is not folded into a returned envelope — it crosses the boundary as a thrown guest
 * exception, which GraalVM wraps in a {@code org.graalvm.polyglot.PolyglotException} here. Callers
 * that need to translate it to a transport status (IMP-M3-04) catch that type explicitly; this
 * class does not swallow it.
 */
public final class GameBridgeClient {

  private static final String ENTRYPOINT_FQCN = "io.turnbase.gamehost.bridge.GameEntrypoint";

  private final Value entrypoint;

  public GameBridgeClient(Context guestContext) {
    this.entrypoint = guestContext.getBindings("java").getMember(ENTRYPOINT_FQCN).newInstance();
  }

  public String createSession(
      String sessionId, String entryPointFqcn, String teamsJson, String parametersJson) {
    return entrypoint
        .invokeMember("createSession", sessionId, entryPointFqcn, teamsJson, parametersJson)
        .asString();
  }

  public String processAction(
      String sessionId, String stateJson, String playerJson, String payloadJson) {
    return entrypoint
        .invokeMember("processAction", sessionId, stateJson, playerJson, payloadJson)
        .asString();
  }

  public String processPlayerJoined(String sessionId, String stateJson, String playerJson) {
    return entrypoint
        .invokeMember("processPlayerJoined", sessionId, stateJson, playerJson)
        .asString();
  }

  public String processPlayerLeft(
      String sessionId, String stateJson, String playerJson, String reason) {
    return entrypoint
        .invokeMember("processPlayerLeft", sessionId, stateJson, playerJson, reason)
        .asString();
  }

  public String resync(String sessionId, String stateJson, String viewerJson, String role) {
    return entrypoint.invokeMember("resync", sessionId, stateJson, viewerJson, role).asString();
  }

  public void disposeSession(String sessionId) {
    entrypoint.invokeMember("disposeSession", sessionId);
  }
}
