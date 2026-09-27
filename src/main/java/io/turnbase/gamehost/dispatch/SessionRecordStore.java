package io.turnbase.gamehost.dispatch;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * CRUD for the Game-Host-owned {@code session:{sessionId}} key (game-host-internals.md §8.1/§13). A
 * single-key JSON blob needs no Lua script for atomicity — one SET/GET/DEL is already atomic. TTL
 * is refreshed on every write (§8.2).
 */
final class SessionRecordStore {

  private static final Duration TTL = Duration.ofHours(24);

  private final StringRedisTemplate redis;

  SessionRecordStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  Optional<SessionRecord> find(String sessionId) {
    String json = redis.opsForValue().get(key(sessionId));
    return json == null ? Optional.empty() : Optional.of(SessionRecordCodec.decode(json));
  }

  void save(String sessionId, SessionRecord record) {
    redis.opsForValue().set(key(sessionId), SessionRecordCodec.encode(record), TTL);
  }

  void delete(String sessionId) {
    redis.delete(key(sessionId));
  }

  private static String key(String sessionId) {
    return "session:" + sessionId;
  }
}
