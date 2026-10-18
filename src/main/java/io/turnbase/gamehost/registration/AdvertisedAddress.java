package io.turnbase.gamehost.registration;

import java.net.UnknownHostException;
import org.jspecify.annotations.Nullable;

/**
 * What {@code RegisterRequest.address} carries (local-environment.md §7.8, IMP-M15-05). A
 * configured value containing {@code :} is used verbatim; without one, the gRPC port is appended;
 * blank or absent falls back to the local host name, and to {@code localhost} when that does not
 * resolve. A bare IPv6 literal contains {@code :} and is taken verbatim, so it must be bracketed
 * with its port.
 */
final class AdvertisedAddress {

  /** The local host name, split out so the unresolvable case is testable. */
  @FunctionalInterface
  interface HostNameSource {
    String get() throws UnknownHostException;
  }

  private AdvertisedAddress() {}

  static String resolve(@Nullable String configured, int grpcPort, HostNameSource hostName) {
    if (configured != null && !configured.isBlank()) {
      String value = configured.strip();
      return value.contains(":") ? value : value + ":" + grpcPort;
    }
    try {
      return hostName.get() + ":" + grpcPort;
    } catch (UnknownHostException e) {
      return "localhost:" + grpcPort;
    }
  }
}
