package io.turnbase.gamehost.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/**
 * local-environment.md §7.8's four-step resolution of {@code turnbase.gamehost.advertised-address}
 * (IMP-M15-05). A bare IPv6 literal is deliberately not covered: it contains {@code :} and is taken
 * verbatim, which §7.8 documents as unsupported.
 */
class AdvertisedAddressTest {

  private static final int GRPC_PORT = 9090;

  @Test
  void configuredValueWithAColonIsUsedVerbatim() {
    assertThat(AdvertisedAddress.resolve("10.1.2.3:7000", GRPC_PORT, () -> "unused"))
        .isEqualTo("10.1.2.3:7000");
  }

  @Test
  void configuredValueWithoutAColonGetsThePortAppended() {
    assertThat(AdvertisedAddress.resolve("10.1.2.3", GRPC_PORT, () -> "unused"))
        .isEqualTo("10.1.2.3:9090");
  }

  @Test
  void blankValueFallsBackToTheHostNameAndPort() {
    assertThat(AdvertisedAddress.resolve("  ", GRPC_PORT, () -> "gh-0")).isEqualTo("gh-0:9090");
  }

  @Test
  void absentValueFallsBackToTheHostNameAndPort() {
    assertThat(AdvertisedAddress.resolve(null, GRPC_PORT, () -> "gh-0")).isEqualTo("gh-0:9090");
  }

  @Test
  void unresolvableHostNameFallsBackToLocalhostAndPort() {
    AdvertisedAddress.HostNameSource unresolvable =
        () -> {
          throw new UnknownHostException("no such host");
        };

    assertThat(AdvertisedAddress.resolve(null, GRPC_PORT, unresolvable))
        .isEqualTo("localhost:9090");
  }
}
