package io.turnbase.gamehost.artifact;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FR-LOAD-003/004: caches fetched backend JARs under {@code cacheDir}, evicting least-recently-used
 * entries once {@code maxSizeBytes} is exceeded — except an entry {@link #pin pinned} for a live
 * context, which is never evicted regardless of size (FR-LOAD-004's hard rule).
 */
public final class ArtifactCache {

  private final ArtifactStore store;
  private final Path cacheDir;
  private final long maxSizeBytes;

  // access-order so the LinkedHashMap's iteration order is itself the LRU order.
  private final Map<String, Long> sizeByKey = new LinkedHashMap<>(16, 0.75f, true);
  private final Set<String> pinned = ConcurrentHashMap.newKeySet();

  public ArtifactCache(ArtifactStore store, Path cacheDir, long maxSizeBytes) {
    this.store = store;
    this.cacheDir = cacheDir;
    this.maxSizeBytes = maxSizeBytes;
  }

  /** Fetches (on a cache miss) and returns the local path to the game's backend JAR. */
  public synchronized Path get(String gameId, String version) {
    String key = cacheKey(gameId, version);
    Path destination = cacheDir.resolve(key.replace('/', '_') + ".jar");
    if (!sizeByKey.containsKey(key) || Files.notExists(destination)) {
      createCacheDirIfNeeded();
      store.download(gameId, version, destination);
      sizeByKey.put(key, sizeOf(destination));
      evictExcess();
    } else {
      // touch: LinkedHashMap's access order only updates on get(), not containsKey().
      sizeByKey.get(key);
    }
    return destination;
  }

  /** Marks {@code (gameId, version)} as backing a live context — never evicted while pinned. */
  public void pin(String gameId, String version) {
    pinned.add(cacheKey(gameId, version));
  }

  /** Releases a previous {@link #pin}; the entry becomes eligible for LRU eviction again. */
  public synchronized void unpin(String gameId, String version) {
    pinned.remove(cacheKey(gameId, version));
    evictExcess();
  }

  private void evictExcess() {
    long total = sizeByKey.values().stream().mapToLong(Long::longValue).sum();
    Iterator<Map.Entry<String, Long>> it = sizeByKey.entrySet().iterator();
    while (total > maxSizeBytes && it.hasNext()) {
      Map.Entry<String, Long> entry = it.next();
      if (pinned.contains(entry.getKey())) {
        continue;
      }
      deleteQuietly(entry.getKey());
      total -= entry.getValue();
      it.remove();
    }
  }

  private void deleteQuietly(String key) {
    try {
      Files.deleteIfExists(cacheDir.resolve(key.replace('/', '_') + ".jar"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void createCacheDirIfNeeded() {
    try {
      Files.createDirectories(cacheDir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static long sizeOf(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String cacheKey(String gameId, String version) {
    return gameId + "/" + version;
  }
}
