package io.turnbase.gamehost.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The bound on the {@code terminated} set: five-minute TTL, 10,000 entries, oldest evicted first.
 */
class TerminatedSessionsTest {

  private final AtomicLong nanos = new AtomicLong();

  @Test
  void theDefaultsAreFiveMinutesAndTenThousandEntries() {
    assertThat(TerminatedSessions.TTL).isEqualTo(Duration.ofMinutes(5));
    assertThat(TerminatedSessions.MAX_ENTRIES).isEqualTo(10_000);
  }

  @Test
  void anEntryIsRememberedUntilItsTtlElapsesAndThenForgotten() {
    TerminatedSessions terminated = new TerminatedSessions(Duration.ofSeconds(10), 100, nanos::get);
    terminated.add("a");

    nanos.set(Duration.ofSeconds(10).toNanos() - 1);
    assertThat(terminated.contains("a")).isTrue();

    nanos.set(Duration.ofSeconds(10).toNanos());
    assertThat(terminated.contains("a")).isFalse();
    assertThat(terminated.size()).isZero();
  }

  @Test
  void whenFullTheOldestEntryIsEvictedFirst() {
    TerminatedSessions terminated = new TerminatedSessions(Duration.ofHours(1), 3, nanos::get);
    for (String id : new String[] {"a", "b", "c", "d"}) {
      terminated.add(id);
      nanos.incrementAndGet();
    }

    assertThat(terminated.size()).isEqualTo(3);
    assertThat(terminated.contains("a")).isFalse();
    assertThat(terminated.contains("b")).isTrue();
    assertThat(terminated.contains("d")).isTrue();
  }

  @Test
  void terminatingAgainRenewsTheEntry() {
    TerminatedSessions terminated = new TerminatedSessions(Duration.ofSeconds(10), 100, nanos::get);
    terminated.add("a");
    nanos.set(Duration.ofSeconds(6).toNanos());
    terminated.add("a");

    nanos.set(Duration.ofSeconds(12).toNanos());
    assertThat(terminated.contains("a")).isTrue();
    assertThat(terminated.size()).isEqualTo(1);
  }

  @Test
  void aHundredThousandTerminationsNeverExceedTheCap() {
    TerminatedSessions terminated = new TerminatedSessions();
    for (int i = 0; i < 100_000; i++) {
      terminated.add("session-" + i);
    }
    assertThat(terminated.size()).isEqualTo(TerminatedSessions.MAX_ENTRIES);
    assertThat(terminated.contains("session-99999")).isTrue();
    assertThat(terminated.contains("session-0")).isFalse();
  }
}
