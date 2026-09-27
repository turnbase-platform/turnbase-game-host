package io.turnbase.gamehost.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.turnbase.gdk.model.GameOutcome;
import io.turnbase.gdk.model.Player;
import io.turnbase.gdk.model.Team;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlatformJsonTest {

  @Test
  void decodeTeamsReadsNestedPlayers() {
    UUID teamId = UUID.randomUUID();
    UUID playerId = UUID.randomUUID();
    String json =
        "[{\"teamId\":\""
            + teamId
            + "\",\"name\":\"Team A\",\"players\":[{\"playerId\":\""
            + playerId
            + "\",\"displayName\":\"Alice\",\"teamId\":\""
            + teamId
            + "\"}]}]";

    var teams = PlatformJson.decodeTeams(json);

    assertThat(teams).hasSize(1);
    Team team = teams.get(0);
    assertThat(team.id()).isEqualTo(teamId);
    assertThat(team.name()).isEqualTo("Team A");
    assertThat(team.players()).hasSize(1);
    Player player = team.players().get(0);
    assertThat(player.id()).isEqualTo(playerId);
    assertThat(player.displayName()).isEqualTo("Alice");
    assertThat(player.teamId()).isEqualTo(teamId);
  }

  @Test
  void decodeTeamsRejectsMalformedJson() {
    assertThatThrownBy(() -> PlatformJson.decodeTeams("not json"))
        .isInstanceOf(CodeLoadException.class);
  }

  @Test
  void decodePlayerRejectsMalformedJson() {
    assertThatThrownBy(() -> PlatformJson.decodePlayer("not json"))
        .isInstanceOf(CodeLoadException.class);
  }

  @Test
  void parseParametersRejectsNonObjectJson() {
    assertThatThrownBy(() -> PlatformJson.parseParameters("[1,2,3]"))
        .isInstanceOf(CodeLoadException.class);
  }

  @Test
  void parseParametersRejectsMalformedJson() {
    assertThatThrownBy(() -> PlatformJson.parseParameters("not json"))
        .isInstanceOf(CodeLoadException.class);
  }

  @Test
  void invocationFailureEncodesStatusFailureKindAndMessage() {
    String json = PlatformJson.invocationFailure("HANDLER_EXCEPTION", "boom");

    assertThat(json).contains("\"status\":\"FAILED\"");
    assertThat(json).contains("\"failureKind\":\"HANDLER_EXCEPTION\"");
    assertThat(json).contains("\"message\":\"boom\"");
  }

  @Test
  void viewFailureEncodesLikeInvocationFailure() {
    String json = PlatformJson.viewFailure("MALFORMED_OUTPUT", "bad view");

    assertThat(json).contains("\"status\":\"FAILED\"");
    assertThat(json).contains("\"failureKind\":\"MALFORMED_OUTPUT\"");
  }

  @Test
  void viewSuccessEncodesTheViewJson() {
    String json = PlatformJson.viewSuccess("{\"count\":1}");

    assertThat(json).contains("\"status\":\"OK\"");
    assertThat(json).contains("\"viewJson\":\"{\\\"count\\\":1}\"");
  }

  @Test
  void invocationSuccessBuildsEveryEffectType() {
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    GameOutcome outcome =
        GameOutcome.builder()
            .teamResult(teamId, 1, Map.of("score", "10"))
            .playerResult(playerId, Map.of("kills", "3"))
            .draw(true)
            .build();

    String json =
        PlatformJson.invocationSuccess("{\"count\":0}")
            .broadcast("{\"a\":1}")
            .sendToPlayer(playerId, "{\"b\":2}")
            .sendToTeam(teamId, "{\"c\":3}")
            .log("hello")
            .gameOver(outcome, "{\"d\":4}")
            .build();

    assertThat(json).contains("\"status\":\"OK\"");
    assertThat(json).contains("\"stateJson\":\"{\\\"count\\\":0}\"");
    assertThat(json).contains("\"type\":\"BROADCAST\"");
    assertThat(json).contains("\"type\":\"SEND_TO_PLAYER\"").contains(playerId.toString());
    assertThat(json).contains("\"type\":\"SEND_TO_TEAM\"").contains(teamId.toString());
    assertThat(json).contains("\"type\":\"LOG\"").contains("hello");
    assertThat(json).contains("\"type\":\"GAME_OVER\"");
    assertThat(json).contains("\"placement\":1");
    assertThat(json).contains("\"score\":\"10\"");
    assertThat(json).contains("\"kills\":\"3\"");
    assertThat(json).contains("\"draw\":true");
  }
}
