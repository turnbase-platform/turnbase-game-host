package io.turnbase.gamehost;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * local-environment.md §7.8's own claim to be the whole Game Host tunable surface, including {@code
 * grpc.port} (IMP-GATE-17) — bound through the real {@code application.yml}, not re-declared.
 */
class GameHostPropertiesTest {

  @Test
  void bindsEveryTunableToItsSection78Default() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(PropertiesConfig.class)
        .run(
            context -> {
              GameHostProperties properties = context.getBean(GameHostProperties.class);

              assertThat(properties.grpc().port()).isEqualTo(9090);
              assertThat(properties.grpc().maxMessageSize()).isEqualTo(DataSize.ofMegabytes(32));
              assertThat(properties.handlerTimeout()).isEqualTo(Duration.ofSeconds(30));
              assertThat(properties.interruptEscalationGrace()).isEqualTo(Duration.ofSeconds(5));
              assertThat(properties.contextIdleEviction()).isEqualTo(Duration.ofMinutes(5));
              assertThat(properties.memoryPressureThreshold()).isEqualTo("80%");
              assertThat(properties.redisUnavailableWindow()).isEqualTo(Duration.ofSeconds(60));
              assertThat(properties.retryLimit()).isEqualTo(3);
              assertThat(properties.guestJavaHome()).isEqualTo("/opt/graalvm-guest-jdk21");
              assertThat(properties.orchestrationAddress()).isEqualTo("platform:8080");
              assertThat(properties.advertisedAddress()).isNull();
            });
  }

  @Test
  void bindsAnExplicitAdvertisedAddress() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(PropertiesConfig.class)
        .withPropertyValues("turnbase.gamehost.advertised-address=10.0.0.9")
        .run(
            context ->
                assertThat(context.getBean(GameHostProperties.class).advertisedAddress())
                    .isEqualTo("10.0.0.9"));
  }

  @Configuration
  @EnableConfigurationProperties(GameHostProperties.class)
  static class PropertiesConfig {}
}
