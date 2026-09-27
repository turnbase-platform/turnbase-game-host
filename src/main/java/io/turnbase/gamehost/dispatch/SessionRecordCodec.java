package io.turnbase.gamehost.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.turnbase.gamehost.espresso.ContextKey;

/** JSON encode/decode for {@link SessionRecord}, the exact shape stored at {@code session:{id}}. */
final class SessionRecordCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SessionRecordCodec() {}

  static String encode(SessionRecord record) {
    ObjectNode node = MAPPER.createObjectNode();
    ObjectNode contextKey = node.putObject("contextKey");
    contextKey.put("gameId", record.contextKey().gameId());
    contextKey.put("version", record.contextKey().version());
    node.put("entryPointFqcn", record.entryPointFqcn());
    node.put("stateJson", record.stateJson());
    node.put("lastAppliedSequence", record.lastAppliedSequence());
    node.put("lastOutcome", record.lastOutcome().name());
    node.put("lastEffectsJson", record.lastEffectsJson());
    String failureKind = record.lastFailureKind();
    if (failureKind != null) {
      node.put("lastFailureKind", failureKind);
    }
    String failureMessage = record.lastFailureMessage();
    if (failureMessage != null) {
      node.put("lastFailureMessage", failureMessage);
    }
    return node.toString();
  }

  static SessionRecord decode(String json) {
    JsonNode node = readTree(json);
    JsonNode contextKeyNode = node.get("contextKey");
    ContextKey contextKey =
        new ContextKey(
            contextKeyNode.get("gameId").asText(), contextKeyNode.get("version").asText());
    return new SessionRecord(
        contextKey,
        node.get("entryPointFqcn").asText(),
        node.get("stateJson").asText(),
        node.get("lastAppliedSequence").asLong(),
        SessionRecord.Outcome.valueOf(node.get("lastOutcome").asText()),
        node.get("lastEffectsJson").asText(),
        node.hasNonNull("lastFailureKind") ? node.get("lastFailureKind").asText() : null,
        node.hasNonNull("lastFailureMessage") ? node.get("lastFailureMessage").asText() : null);
  }

  private static JsonNode readTree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("malformed session record JSON", e);
    }
  }
}
