package io.turnbase.gamehost.dispatch;

import io.turnbase.gamehost.espresso.ContextKey;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** JSON encode/decode for {@link SessionRecord}, the exact shape stored at {@code session:{id}}. */
final class SessionRecordCodec {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
            contextKeyNode.get("gameId").asString(), contextKeyNode.get("version").asString());
    return new SessionRecord(
        contextKey,
        node.get("entryPointFqcn").asString(),
        node.get("stateJson").asString(),
        node.get("lastAppliedSequence").asLong(),
        SessionRecord.Outcome.valueOf(node.get("lastOutcome").asString()),
        node.get("lastEffectsJson").asString(),
        node.hasNonNull("lastFailureKind") ? node.get("lastFailureKind").asString() : null,
        node.hasNonNull("lastFailureMessage") ? node.get("lastFailureMessage").asString() : null);
  }

  private static JsonNode readTree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JacksonException e) {
      throw new IllegalStateException("malformed session record JSON", e);
    }
  }
}
