package io.turnbase.gamehost.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.turnbase.gdk.model.GameParameters;
import io.turnbase.gdk.model.ParameterAccessException;
import java.util.Set;

/**
 * Wraps the flat {@code parametersJson} object materialized by Orchestration (§3.4) — every
 * declared parameter is present, so a missing key is a bridge/orchestration bug, not something a
 * game handler should see as "absent". {@link #contains} and {@link #keys} still work off the
 * object's actual fields, since that flat object is exactly the set of declared keys.
 */
final class GameParametersImpl implements GameParameters {

  private final ObjectNode node;

  GameParametersImpl(ObjectNode node) {
    this.node = node;
  }

  @Override
  public int getInt(String key) {
    JsonNode value = require(key);
    if (!value.isIntegralNumber()) {
      throw new ParameterAccessException("parameter '" + key + "' is not an integer");
    }
    return value.intValue();
  }

  @Override
  public double getDecimal(String key) {
    JsonNode value = require(key);
    if (!value.isNumber()) {
      throw new ParameterAccessException("parameter '" + key + "' is not a decimal");
    }
    return value.doubleValue();
  }

  @Override
  public boolean getBoolean(String key) {
    JsonNode value = require(key);
    if (!value.isBoolean()) {
      throw new ParameterAccessException("parameter '" + key + "' is not a boolean");
    }
    return value.booleanValue();
  }

  @Override
  public String getString(String key) {
    JsonNode value = require(key);
    if (!value.isTextual()) {
      throw new ParameterAccessException("parameter '" + key + "' is not a string");
    }
    return value.textValue();
  }

  @Override
  public String getEnum(String key) {
    JsonNode value = require(key);
    if (!value.isTextual()) {
      throw new ParameterAccessException("parameter '" + key + "' is not an enum");
    }
    return value.textValue();
  }

  @Override
  public boolean contains(String key) {
    return node.has(key);
  }

  @Override
  public Set<String> keys() {
    Set<String> keys = new java.util.LinkedHashSet<>();
    node.fieldNames().forEachRemaining(keys::add);
    return Set.copyOf(keys);
  }

  private JsonNode require(String key) {
    JsonNode value = node.get(key);
    if (value == null) {
      throw new ParameterAccessException("no such parameter: " + key);
    }
    return value;
  }
}
