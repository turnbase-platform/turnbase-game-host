package io.turnbase.gamehost.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.turnbase.gdk.model.GameOutcome;
import io.turnbase.gdk.model.Player;
import io.turnbase.gdk.model.Team;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The platform-fixed schema for the types that cross the guest boundary as JSON but are not
 * game-defined — {@code teamsJson}/{@code playerJson} (mirroring proto {@code Team}/{@code
 * PlayerRef}) and the {@code InvocationEnvelope}/{@code ViewEnvelope} themselves
 * (game-host-internals.md §3.3). A separate, plain {@code ObjectMapper} from the one {@link
 * io.turnbase.serialization.GdkSerialization} uses for opaque game state/payloads: this schema is
 * platform-owned, not subject to the game's binding rules, and never touches a developer type.
 */
final class PlatformJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private PlatformJson() {}

  static List<Team> decodeTeams(String teamsJson) {
    try {
      List<Team> teams = new ArrayList<>();
      for (JsonNode teamNode : MAPPER.readTree(teamsJson)) {
        List<Player> players = new ArrayList<>();
        for (JsonNode playerNode : teamNode.path("players")) {
          players.add(decodePlayer(playerNode));
        }
        teams.add(
            new Team(
                UUID.fromString(teamNode.get("teamId").asText()),
                teamNode.get("name").asText(),
                players));
      }
      return List.copyOf(teams);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new CodeLoadException("malformed teamsJson", e);
    }
  }

  static Player decodePlayer(String playerJson) {
    try {
      return decodePlayer(MAPPER.readTree(playerJson));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new CodeLoadException("malformed playerJson", e);
    }
  }

  private static Player decodePlayer(JsonNode node) {
    return new Player(
        UUID.fromString(node.get("playerId").asText()),
        node.get("displayName").asText(),
        UUID.fromString(node.get("teamId").asText()));
  }

  static ObjectNode parseParameters(String parametersJson) {
    try {
      JsonNode node = MAPPER.readTree(parametersJson);
      if (!(node instanceof ObjectNode objectNode)) {
        throw new CodeLoadException("parametersJson is not a JSON object");
      }
      return objectNode;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new CodeLoadException("malformed parametersJson", e);
    }
  }

  private static ObjectNode encodeOutcome(GameOutcome outcome) {
    ObjectNode node = MAPPER.createObjectNode();
    ArrayNode teamResults = node.putArray("teamResults");
    for (GameOutcome.TeamResult result : outcome.teamResults()) {
      ObjectNode resultNode = teamResults.addObject();
      resultNode.put("teamId", result.teamId().toString());
      resultNode.put("placement", result.placement());
      ObjectNode stats = resultNode.putObject("stats");
      for (Map.Entry<String, String> entry : result.stats().entrySet()) {
        stats.put(entry.getKey(), entry.getValue());
      }
    }
    ArrayNode playerResults = node.putArray("playerResults");
    for (GameOutcome.PlayerResult result : outcome.playerResults()) {
      ObjectNode resultNode = playerResults.addObject();
      resultNode.put("playerId", result.playerId().toString());
      ObjectNode stats = resultNode.putObject("stats");
      for (Map.Entry<String, String> entry : result.stats().entrySet()) {
        stats.put(entry.getKey(), entry.getValue());
      }
    }
    node.put("draw", outcome.draw());
    return node;
  }

  static String invocationFailure(String failureKind, String message) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("status", "FAILED");
    node.put("failureKind", failureKind);
    node.put("message", message);
    return node.toString();
  }

  static String viewFailure(String failureKind, String message) {
    return invocationFailure(failureKind, message);
  }

  static String viewSuccess(String viewJson) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("status", "OK");
    node.put("viewJson", viewJson);
    return node.toString();
  }

  static EnvelopeBuilder invocationSuccess(String stateJson) {
    return new EnvelopeBuilder(stateJson);
  }

  /** Accumulates the ordered {@code effects[]} array for an {@code InvocationEnvelope}. */
  static final class EnvelopeBuilder {
    private final ObjectNode node;
    private final ArrayNode effects;

    private EnvelopeBuilder(String stateJson) {
      this.node = MAPPER.createObjectNode();
      node.put("status", "OK");
      node.put("stateJson", stateJson);
      this.effects = node.putArray("effects");
    }

    EnvelopeBuilder broadcast(String payloadJson) {
      effects.addObject().put("type", "BROADCAST").put("payloadJson", payloadJson);
      return this;
    }

    EnvelopeBuilder sendToPlayer(UUID playerId, String payloadJson) {
      effects
          .addObject()
          .put("type", "SEND_TO_PLAYER")
          .put("playerId", playerId.toString())
          .put("payloadJson", payloadJson);
      return this;
    }

    EnvelopeBuilder sendToTeam(UUID teamId, String payloadJson) {
      effects
          .addObject()
          .put("type", "SEND_TO_TEAM")
          .put("teamId", teamId.toString())
          .put("payloadJson", payloadJson);
      return this;
    }

    EnvelopeBuilder log(String text) {
      effects.addObject().put("type", "LOG").put("text", text);
      return this;
    }

    EnvelopeBuilder gameOver(GameOutcome outcome, String resultsPayloadJson) {
      ObjectNode effect = effects.addObject();
      effect.put("type", "GAME_OVER");
      effect.set("outcome", encodeOutcome(outcome));
      effect.put("resultsPayloadJson", resultsPayloadJson);
      return this;
    }

    String build() {
      return node.toString();
    }
  }
}
