package io.turnbase.gamehost.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.turnbase.grpc.gamehost.v1.PlayerRef;
import io.turnbase.grpc.gamehost.v1.Team;
import java.util.List;

/**
 * Encodes proto {@link Team}/{@link PlayerRef} into the platform-fixed {@code teamsJson}/{@code
 * playerJson} schema the guest bridge decodes (game-host-internals.md §3.3, mirrors {@code
 * io.turnbase.gamehost.bridge.PlatformJson}'s decode side).
 */
final class TeamsJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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
