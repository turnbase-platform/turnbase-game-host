package io.turnbase.gamehost.espresso;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * §6's context/session model: one context per {@link ContextKey}, created on demand and disposed by
 * idle timeout (FR-CTX-009) or LRU-under-memory-pressure (FR-CTX-010) — never while it has an
 * active session (§6.3).
 */
public final class GameContextManager implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(GameContextManager.class);

  private final EspressoContextFactory contextFactory;
  private final Duration idleEviction;
  private final double memoryPressureThreshold;
  private final DoubleSupplier memoryUsageFraction;
  private final Supplier<Instant> clock;
  private final BootWatchdog watchdog;

  private final Map<ContextKey, ManagedContext> contexts = new ConcurrentHashMap<>();
  private final Map<ContextKey, CompletableFuture<ManagedContext>> creating =
      new ConcurrentHashMap<>();

  public GameContextManager(
      EspressoContextFactory contextFactory,
      Duration idleEviction,
      double memoryPressureThreshold,
      DoubleSupplier memoryUsageFraction,
      Supplier<Instant> clock,
      BootWatchdog watchdog) {
    this.watchdog = watchdog;
    this.contextFactory = contextFactory;
    this.idleEviction = idleEviction;
    this.memoryPressureThreshold = memoryPressureThreshold;
    this.memoryUsageFraction = memoryUsageFraction;
    this.clock = clock;
  }

  public GameContextManager(
      EspressoContextFactory contextFactory,
      Duration idleEviction,
      double memoryPressureThreshold,
      DoubleSupplier memoryUsageFraction,
      Supplier<Instant> clock) {
    this(
        contextFactory,
        idleEviction,
        memoryPressureThreshold,
        memoryUsageFraction,
        clock,
        BootWatchdog.disabled());
  }

  public GameContextManager(
      EspressoContextFactory contextFactory,
      Duration idleEviction,
      double memoryPressureThreshold,
      BootWatchdog watchdog) {
    this(
        contextFactory,
        idleEviction,
        memoryPressureThreshold,
        GameContextManager::heapUsageFraction,
        Instant::now,
        watchdog);
  }

  public GameContextManager(
      EspressoContextFactory contextFactory,
      Duration idleEviction,
      double memoryPressureThreshold) {
    this(contextFactory, idleEviction, memoryPressureThreshold, BootWatchdog.disabled());
  }

  /**
   * Returns the live context for {@code key}, creating it on the first call. Concurrent callers for
   * the same cold key are coalesced behind a single creation (FR-CTX-002): the first registers a
   * future in {@code creating} and builds the context; the rest wait on that future. The build runs
   * outside any map lock, so a slow boot for one key never holds up {@code getOrCreate} or {@code
   * get} for another (IMP-M16-04). A failure reaches every waiter and is not cached.
   */
  public ManagedContext getOrCreate(ContextKey key, Path gameArtifactPath) {
    ManagedContext live = contexts.get(key);
    if (live != null) {
      return live;
    }
    CompletableFuture<ManagedContext> mine = new CompletableFuture<>();
    CompletableFuture<ManagedContext> other = creating.putIfAbsent(key, mine);
    if (other != null) {
      return await(other);
    }
    try {
      // A creation may have finished between the first look and the registration above.
      ManagedContext created = contexts.get(key);
      if (created == null) {
        created =
            new ManagedContext(key, contextFactory.create(gameArtifactPath), clock.get(), watchdog);
        contexts.put(key, created);
        log.info(
            "Game context created {} {}",
            kv("gameId", key.gameId()),
            kv("gameVersion", key.version()));
      }
      mine.complete(created);
      return created;
    } catch (RuntimeException | Error e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      creating.remove(key, mine);
    }
  }

  private static ManagedContext await(CompletableFuture<ManagedContext> creation) {
    try {
      return creation.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw e;
    }
  }

  /** The live context for {@code key}, if any; never creates one. */
  public Optional<ManagedContext> find(ContextKey key) {
    return Optional.ofNullable(contexts.get(key));
  }

  public void sessionStarted(ContextKey key) {
    ManagedContext context = contexts.get(key);
    if (context != null) {
      context.sessionStarted(clock.get());
    }
  }

  public void sessionEnded(ContextKey key) {
    ManagedContext context = contexts.get(key);
    if (context != null) {
      context.sessionEnded(clock.get());
    }
  }

  /** FR-REG-002: the set reported to Orchestration for warm-host selection. */
  public Set<ContextKey> liveContextKeys() {
    return Set.copyOf(contexts.keySet());
  }

  /** The {@code active_sessions} load signal of the heartbeat (§11). */
  public int activeSessionCount() {
    return contexts.values().stream().mapToInt(ManagedContext::activeSessionCount).sum();
  }

  /** FR-CTX-009: contexts idle (no active session) past {@code idleEviction} are disposed. */
  public void evictIdle() {
    Instant now = clock.get();
    contexts.values().stream()
        .filter(context -> !context.hasActiveSessions())
        .filter(context -> Duration.between(context.lastUsed(), now).compareTo(idleEviction) >= 0)
        .map(ManagedContext::key)
        .toList()
        .forEach(key -> dispose(key, "IDLE_TIMEOUT"));
  }

  /**
   * FR-CTX-010: once heap usage crosses {@code memoryPressureThreshold}, evict the single
   * least-recently-used idle context, regardless of its idle timer. A context with any active
   * session is never a candidate.
   */
  public void evictUnderMemoryPressure() {
    if (memoryUsageFraction.getAsDouble() < memoryPressureThreshold) {
      return;
    }
    contexts.values().stream()
        .filter(context -> !context.hasActiveSessions())
        .min(Comparator.comparing(ManagedContext::lastUsed))
        .map(ManagedContext::key)
        .ifPresent(key -> dispose(key, "MEMORY_PRESSURE"));
  }

  private void dispose(ContextKey key, String reason) {
    ManagedContext context = contexts.remove(key);
    if (context != null) {
      context.close();
      log.info(
          "Game context released {} {} {}",
          kv("gameId", key.gameId()),
          kv("gameVersion", key.version()),
          kv("reason", reason));
    }
  }

  @Override
  public void close() {
    Set.copyOf(contexts.keySet()).forEach(key -> dispose(key, "SHUTDOWN"));
  }

  private static double heapUsageFraction() {
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    long used = memory.getHeapMemoryUsage().getUsed();
    long max = memory.getHeapMemoryUsage().getMax();
    if (max <= 0) {
      return 0.0;
    }
    return (double) used / max;
  }
}
