package io.turnbase.gamehost.shim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@code io.turnbase.gamehost.bridge.GameEntrypoint} through a real Espresso ("java") guest
 * context, via {@link GameBridgeClient} exactly as production would (game-host-internals.md §3.3):
 * every value crossing {@link GameBridgeClient}'s API in this test is a {@code String} or
 * primitive, proving the host shim never touches a guest object.
 *
 * <p>{@code java.Classpath} is set to this JVM's own test classpath ({@code java.class.path})
 * rather than built via {@code GuestClasspathBuilder}/{@code EspressoContextFactory}: the shaded
 * {@code guest-bridge} jar those expect is a {@code prepare-package}-phase artifact of this same
 * module (pom.xml's {@code maven-shade-plugin} execution) and does not exist yet when tests run.
 * The test classpath already contains this module's compiled {@code io.turnbase.gamehost.bridge}
 * classes, the {@code turnbase-sdk} and {@code turnbase-serialization} jars, Jackson, and the
 * {@code fixture.CountingGame} test class — the same four ingredients the real shaded jar plus game
 * jar would put on the guest classpath, so this exercises the real entrypoint honestly without
 * needing the packaging step to have run.
 */
class GameBridgeClientEspressoTest {

  private static final String ENTRY_POINT_FQCN = "io.turnbase.gamehost.bridge.fixture.CountingGame";

  private Context context;
  private GameBridgeClient client;

  @BeforeEach
  void createContext() {
    context =
        Context.newBuilder("java")
            .allowExperimentalOptions(true)
            .allowNativeAccess(true)
            .option("java.JavaHome", System.getProperty("java.home"))
            .option("java.ExitHost", "false")
            .option("java.Classpath", System.getProperty("java.class.path"))
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLoading(false)
            .allowCreateProcess(false)
            .allowEnvironmentAccess(EnvironmentAccess.NONE)
            .build();
    client = new GameBridgeClient(context);
  }

  @AfterEach
  void disposeContext() {
    context.close();
  }

  @Test
  void createSessionRunsOnInitAndReturnsAnOkInvocationEnvelope() {
    String sessionId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();

    String envelope =
        client.createSession(
            sessionId, ENTRY_POINT_FQCN, teamsJson(teamId, playerId), "{\"start\":0}");

    assertThat(envelope).contains("\"status\":\"OK\"");
    assertThat(envelope).contains("\"stateJson\":\"{\\\"count\\\":0}\"");
    assertThat(envelope).contains("\"type\":\"LOG\"");

    client.disposeSession(sessionId);
  }

  @Test
  void processActionAdvancesStateAndFansOutASendToPlayerEffect() {
    String sessionId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    client.createSession(sessionId, ENTRY_POINT_FQCN, teamsJson(teamId, playerId), "{\"start\":0}");

    String envelope =
        client.processAction(
            sessionId, "{\"count\":0}", playerJson(playerId, teamId), "{\"by\":3}");

    assertThat(envelope).contains("\"status\":\"OK\"");
    assertThat(envelope).contains("\"stateJson\":\"{\\\"count\\\":3}\"");
    assertThat(envelope).contains("\"type\":\"SEND_TO_PLAYER\"");
    assertThat(envelope).contains(playerId.toString());

    client.disposeSession(sessionId);
  }

  @Test
  void processActionReachingTargetEmitsAGameOverEffect() {
    String sessionId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    client.createSession(sessionId, ENTRY_POINT_FQCN, teamsJson(teamId, playerId), "{\"start\":0}");

    String envelope =
        client.processAction(
            sessionId, "{\"count\":0}", playerJson(playerId, teamId), "{\"by\":10}");

    assertThat(envelope).contains("\"status\":\"OK\"");
    assertThat(envelope).contains("\"type\":\"GAME_OVER\"");
    assertThat(envelope).contains("\"draw\":false");
    assertThat(envelope).contains(playerId.toString());

    client.disposeSession(sessionId);
  }

  @Test
  void resyncIsReadOnlyAndReturnsAViewEnvelope() {
    String sessionId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    client.createSession(sessionId, ENTRY_POINT_FQCN, teamsJson(teamId, playerId), "{\"start\":0}");

    String envelope =
        client.resync(sessionId, "{\"count\":7}", playerJson(playerId, teamId), "PLAYER");

    assertThat(envelope).contains("\"status\":\"OK\"");
    assertThat(envelope).contains("\"viewJson\":\"{\\\"count\\\":7}\"");

    client.disposeSession(sessionId);
  }

  @Test
  void malformedActionPayloadSurfacesAsHandlerException() {
    String sessionId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    client.createSession(sessionId, ENTRY_POINT_FQCN, teamsJson(teamId, playerId), "{\"start\":0}");

    String envelope =
        client.processAction(
            sessionId,
            "{\"count\":0}",
            playerJson(playerId, teamId),
            "\"not an Increment object\"");

    assertThat(envelope).contains("\"status\":\"FAILED\"");
    assertThat(envelope).contains("\"failureKind\":\"HANDLER_EXCEPTION\"");

    client.disposeSession(sessionId);
  }

  private static String teamsJson(UUID teamId, UUID playerId) {
    return "[{\"teamId\":\""
        + teamId
        + "\",\"name\":\"Team A\",\"players\":["
        + playerJson(playerId, teamId)
        + "]}]";
  }

  private static String playerJson(UUID playerId, UUID teamId) {
    return "{\"playerId\":\""
        + playerId
        + "\",\"displayName\":\"Alice\",\"teamId\":\""
        + teamId
        + "\"}";
  }
}
