package io.turnbase.gamehost.dispatch;

import io.turnbase.grpc.gamehost.v1.PlayerRef;
import io.turnbase.grpc.gamehost.v1.Team;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes proto {@link Team}/{@link PlayerRef} into the platform-fixed {@code teamsJson}/{@code
 * playerJson} schema the guest bridge decodes (game-host-internals.md §3.3, mirrors {@code
 * io.turnbase.gamehost.bridge.PlatformJson}'s decode side).
 */
final class TeamsJson {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private TeamsJson() {}

  static String encode(List<Team> teams) {
    ArrayNode array = MAPPER.createArrayNode();
    for (Team team : teams) {
      ObjectNode teamNode = array.addObject();
      teamNode.put("teamId", team.getTeamId());
      teamNode.put("name", team.getName());
      ArrayNode players = teamNode.putArray("players");
      for (PlayerRef player : team.getPlayersList()) {
        players.add(playerNode(player));
      }
    }
    return array.toString();
  }

  static String encode(PlayerRef player) {
    return playerNode(player).toString();
  }

  private static ObjectNode playerNode(PlayerRef player) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("playerId", player.getPlayerId());
    node.put("displayName", player.getDisplayName());
    node.put("teamId", player.getTeamId());
    return node;
  }
}
