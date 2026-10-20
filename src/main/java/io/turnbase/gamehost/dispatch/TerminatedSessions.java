package io.turnbase.gamehost.dispatch;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Sessions terminated on this host. It exists to reject a late input and to discard an in-flight
 * result, so a discarded result never resurrects a deleted Redis record (game-host-internals.md
 * §9.1) — not to remember every session for ever. Entries therefore expire and the set is capped.
 *
 * <p>Insertion-ordered, so the oldest entry is always at the head: expiry and cap eviction both
 * only look there. Every method is {@code synchronized}; the critical sections are a few map
 * operations.
 */
final class TerminatedSessions {

  /**
   * How long a termination is remembered. The spec has no time-based retry window, only a retry
   * count of 3 (game-host-internals.md §15), and each attempt can last up to the dispatch deadline
   * of 35 s ({@code turnbase.orchestration.dispatch-deadline}), so the longest a late input or
   * result can still arrive is 3 x 35 s = 105 s. Five minutes is about three times that.
   */
  static final Duration TTL = Duration.ofMinutes(5);

  /** Upper bound on remembered terminations; the oldest are evicted first. */
  static final int MAX_ENTRIES = 10_000;

  private final Map<String, Long> terminatedAtNanos = new LinkedHashMap<>();
  private final long ttlNanos;
  private final int maxEntries;
  private final LongSupplier nanoClock;

  TerminatedSessions() {
    this(TTL, MAX_ENTRIES, System::nanoTime);
  }

  TerminatedSessions(Duration ttl, int maxEntries, LongSupplier nanoClock) {
    this.ttlNanos = ttl.toNanos();
    this.maxEntries = maxEntries;
    this.nanoClock = nanoClock;
  }

  synchronized void add(String sessionId) {
    long now = nanoClock.getAsLong();
    expire(now);
    // Re-termination moves the entry to the tail, so it is remembered for a full TTL again.
    terminatedAtNanos.remove(sessionId);
    terminatedAtNanos.put(sessionId, now);
    while (terminatedAtNanos.size() > maxEntries) {
      Iterator<String> oldest = terminatedAtNanos.keySet().iterator();
      oldest.next();
      oldest.remove();
    }
  }

  synchronized boolean contains(String sessionId) {
    expire(nanoClock.getAsLong());
    return terminatedAtNanos.containsKey(sessionId);
  }

  synchronized int size() {
    expire(nanoClock.getAsLong());
    return terminatedAtNanos.size();
  }

  private void expire(long now) {
    Iterator<Long> times = terminatedAtNanos.values().iterator();
    while (times.hasNext() && now - times.next() >= ttlNanos) {
      times.remove();
    }
  }
}
