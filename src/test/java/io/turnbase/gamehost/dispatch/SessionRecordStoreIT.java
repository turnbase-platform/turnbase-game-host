package io.turnbase.gamehost.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.gamehost.espresso.ContextKey;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * §8.1/§13 against a real Redis — conventions.md §3: Testcontainers, never Docker Compose. No
 * Spring context is booted (mirrors {@code S3ArtifactStoreIT}'s lightweight style): the connection
 * is wired by hand.
 */
@Testcontainers
class SessionRecordStoreIT {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private final LettuceConnectionFactory connectionFactory = connectionFactory();
  private final SessionRecordStore store = new SessionRecordStore(redisTemplate(connectionFactory));

  private static LettuceConnectionFactory connectionFactory() {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    factory.afterPropertiesSet();
    return factory;
  }

  private static StringRedisTemplate redisTemplate(LettuceConnectionFactory connectionFactory) {
    StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
    return template;
  }

  @AfterEach
  void closeConnection() {
    connectionFactory.destroy();
  }

  @Test
  void aSavedRecordRoundTripsExactly() {
    String sessionId = UUID.randomUUID().toString();
    SessionRecord record =
        new SessionRecord(
            new ContextKey("tic-tac-toe", "1.0.0"),
            "io.turnbase.gamehost.bridge.fixture.CountingGame",
            "{\"count\":3}",
            2,
            SessionRecord.Outcome.OK,
            "[{\"type\":\"LOG\",\"text\":\"hi\"}]",
            null,
            null);

    store.save(sessionId, record);

    assertThat(store.find(sessionId)).contains(record);
  }

  @Test
  void aFailedOutcomeRoundTripsItsFailureFields() {
    String sessionId = UUID.randomUUID().toString();
    SessionRecord record =
        new SessionRecord(
            new ContextKey("tic-tac-toe", "1.0.0"),
            "io.turnbase.gamehost.bridge.fixture.CountingGame",
            "{\"count\":3}",
            5,
            SessionRecord.Outcome.FAILED,
            "[]",
            "HANDLER_EXCEPTION",
            "boom");

    store.save(sessionId, record);

    assertThat(store.find(sessionId)).contains(record);
  }

  @Test
  void deleteRemovesTheRecord() {
    String sessionId = UUID.randomUUID().toString();
    store.save(
        sessionId,
        new SessionRecord(
            new ContextKey("g", "1.0.0"),
            "fqcn",
            "{}",
            0,
            SessionRecord.Outcome.OK,
            "[]",
            null,
            null));

    store.delete(sessionId);

    assertThat(store.find(sessionId)).isEqualTo(Optional.empty());
  }

  @Test
  void findReturnsEmptyForAnUnknownSession() {
    assertThat(store.find(UUID.randomUUID().toString())).isEmpty();
  }
}
