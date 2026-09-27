package io.turnbase.gamehost.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.turnbase.gdk.model.ParameterAccessException;
import org.junit.jupiter.api.Test;

class GameParametersImplTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static GameParametersImpl paramsOf(String json) {
    try {
      return new GameParametersImpl((ObjectNode) MAPPER.readTree(json));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void readsEveryDeclaredParameterType() {
    GameParametersImpl params =
        paramsOf(
            "{\"count\":3,\"ratio\":1.5,\"ranked\":true,\"label\":\"gold\",\"mode\":\"BLITZ\"}");

    assertThat(params.getInt("count")).isEqualTo(3);
    assertThat(params.getDecimal("ratio")).isEqualTo(1.5);
    assertThat(params.getBoolean("ranked")).isTrue();
    assertThat(params.getString("label")).isEqualTo("gold");
    assertThat(params.getEnum("mode")).isEqualTo("BLITZ");
    assertThat(params.contains("count")).isTrue();
    assertThat(params.contains("missing")).isFalse();
    assertThat(params.keys())
        .containsExactlyInAnyOrder("count", "ratio", "ranked", "label", "mode");
  }

  @Test
  void missingKeyThrowsParameterAccessException() {
    GameParametersImpl params = paramsOf("{}");

    assertThatThrownBy(() -> params.getInt("count"))
        .isInstanceOf(ParameterAccessException.class)
        .hasMessageContaining("no such parameter");
  }

  @Test
  void wrongTypeThrowsParameterAccessExceptionForEveryGetter() {
    GameParametersImpl params = paramsOf("{\"value\":\"not-a-number-or-bool\"}");

    assertThatThrownBy(() -> params.getInt("value")).isInstanceOf(ParameterAccessException.class);
    assertThatThrownBy(() -> params.getDecimal("value"))
        .isInstanceOf(ParameterAccessException.class);
    assertThatThrownBy(() -> params.getBoolean("value"))
        .isInstanceOf(ParameterAccessException.class);

    GameParametersImpl numeric = paramsOf("{\"value\":7}");
    assertThatThrownBy(() -> numeric.getString("value"))
        .isInstanceOf(ParameterAccessException.class);
    assertThatThrownBy(() -> numeric.getEnum("value")).isInstanceOf(ParameterAccessException.class);
  }
}
