package io.turnbase.gamehost.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.turnbase.grpc.gamehost.v1.BroadcastEffect;
import io.turnbase.grpc.gamehost.v1.Effect;
import io.turnbase.grpc.gamehost.v1.GameFailure;
import io.turnbase.grpc.gamehost.v1.GameFailureKind;
import io.turnbase.grpc.gamehost.v1.GameOutcome;
import io.turnbase.grpc.gamehost.v1.GameOverEffect;
import io.turnbase.grpc.gamehost.v1.LogEffect;
import io.turnbase.grpc.gamehost.v1.PlayerResult;
import io.turnbase.grpc.gamehost.v1.SendToPlayerEffect;
import io.turnbase.grpc.gamehost.v1.SendToTeamEffect;
import io.turnbase.grpc.gamehost.v1.TeamResult;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Decodes the InvocationEnvelope/ViewEnvelope JSON that crosses the guest boundary
 * (game-host-internals.md §3.3) into proto types, mirroring the encode schema in {@code
 * io.turnbase.gamehost.bridge.PlatformJson} from the host side of the boundary.
 */
final class EnvelopeCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private EnvelopeCodec() {}

  record ParsedInvocation(
      boolean ok,
      String stateJson,
      List<Effect> effects,
      String effectsRawJson,
      @Nullable GameFailure failure) {}

  record ParsedView(boolean ok, String viewJson, @Nullable GameFailure failure) {}

  static ParsedInvocation decodeInvocation(String envelopeJson) {
    JsonNode node = readTree(envelopeJson);
    if ("OK".equals(node.get("status").asText())) {
      String stateJson = node.get("stateJson").asText();
      JsonNode effectsNode = node.get("effects");
      return new ParsedInvocation(
          true, stateJson, decodeEffects(effectsNode), effectsNode.toString(), null);
    }
    GameFailure failure = failure(node.get("failureKind").asText(), node.get("message").asText());
    return new ParsedInvocation(false, "", List.of(), "[]", failure);
  }

  static ParsedView decodeView(String envelopeJson) {
    JsonNode node = readTree(envelopeJson);
    if ("OK".equals(node.get("status").asText())) {
      return new ParsedView(true, node.get("viewJson").asText(), null);
    }
    GameFailure failure = failure(node.get("failureKind").asText(), node.get("message").asText());
    return new ParsedView(false, "", failure);
  }

  static List<Effect> decodeEffectsArray(String effectsJsonArray) {
    return decodeEffects(readTree(effectsJsonArray));
  }

  static GameFailure failure(String kind, String message) {
    return GameFailure.newBuilder()
        .setKind(GameFailureKind.valueOf("GAME_FAILURE_KIND_" + kind))
        .setMessage(message)
        .build();
  }

  private static List<Effect> decodeEffects(JsonNode effectsNode) {
    List<Effect> effects = new ArrayList<>();
    for (JsonNode effectNode : effectsNode) {
      effects.add(decodeEffect(effectNode));
    }
    return List.copyOf(effects);
  }

  private static Effect decodeEffect(JsonNode node) {
    String type = node.get("type").asText();
    return switch (type) {
      case "BROADCAST" ->
          Effect.newBuilder()
              .setBroadcast(BroadcastEffect.newBuilder().setPayload(utf8(node.get("payloadJson"))))
              .build();
      case "SEND_TO_PLAYER" ->
          Effect.newBuilder()
              .setSendToPlayer(
                  SendToPlayerEffect.newBuilder()
                      .setPlayerId(node.get("playerId").asText())
                      .setPayload(utf8(node.get("payloadJson"))))
              .build();
      case "SEND_TO_TEAM" ->
          Effect.newBuilder()
              .setSendToTeam(
                  SendToTeamEffect.newBuilder()
                      .setTeamId(node.get("teamId").asText())
                      .setPayload(utf8(node.get("payloadJson"))))
              .build();
      case "LOG" ->
          Effect.newBuilder()
              .setLog(LogEffect.newBuilder().setEntry(node.get("text").asText()))
              .build();
      case "GAME_OVER" ->
          Effect.newBuilder()
              .setGameOver(
                  GameOverEffect.newBuilder()
                      .setOutcome(decodeOutcome(node.get("outcome")))
                      .setResultsPayload(utf8(node.get("resultsPayloadJson"))))
              .build();
      default -> throw new IllegalStateException("unknown effect type: " + type);
    };
  }

  private static GameOutcome decodeOutcome(JsonNode node) {
    GameOutcome.Builder outcome = GameOutcome.newBuilder().setDraw(node.get("draw").asBoolean());
    for (JsonNode teamResult : node.get("teamResults")) {
      outcome.addTeamResults(
          TeamResult.newBuilder()
              .setTeamId(teamResult.get("teamId").asText())
              .setPlacement(teamResult.get("placement").asInt())
              .putAllStats(stringMap(teamResult.get("stats")))
              .build());
    }
    for (JsonNode playerResult : node.get("playerResults")) {
      outcome.addPlayerResults(
          PlayerResult.newBuilder()
              .setPlayerId(playerResult.get("playerId").asText())
              .putAllStats(stringMap(playerResult.get("stats")))
              .build());
    }
    return outcome.build();
  }

  private static Map<String, String> stringMap(JsonNode node) {
    Map<String, String> map = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      map.put(entry.getKey(), entry.getValue().asText());
    }
    return map;
  }

  private static ByteString utf8(JsonNode node) {
    return ByteString.copyFromUtf8(node.asText());
  }

  private static JsonNode readTree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("malformed envelope JSON from guest bridge", e);
    }
  }
}
