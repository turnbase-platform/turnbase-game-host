package io.turnbase.gamehost.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FR-LOAD-003/004: cache hits skip the fetch, and LRU eviction never touches a pinned entry. */
class ArtifactCacheTest {

  @TempDir Path cacheDir;

  private static final int ARTIFACT_SIZE = 100;

  private ArtifactStore fakeStoreWritingBytes(AtomicInteger fetchCount, int size) {
    return (gameId, version, destination) -> {
      fetchCount.incrementAndGet();
      try {
        Files.write(destination, new byte[size]);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
  }

  @Test
  void aCacheHitSkipsTheFetch() {
    AtomicInteger fetchCount = new AtomicInteger();
    ArtifactCache cache =
        new ArtifactCache(fakeStoreWritingBytes(fetchCount, ARTIFACT_SIZE), cacheDir, 10_000);

    Path first = cache.get("tic-tac-toe", "1.0.0");
    Path second = cache.get("tic-tac-toe", "1.0.0");

    assertThat(fetchCount.get()).isEqualTo(1);
    assertThat(first).isEqualTo(second);
    assertThat(first).exists();
  }

  @Test
  void lruEvictsTheLeastRecentlyUsedEntryOnceOverTheSizeLimit() {
    AtomicInteger fetchCount = new AtomicInteger();
    // Room for exactly one artifact at a time.
    ArtifactCache cache =
        new ArtifactCache(
            fakeStoreWritingBytes(fetchCount, ARTIFACT_SIZE), cacheDir, ARTIFACT_SIZE);

    Path oldest = cache.get("older-game", "1.0.0");
    cache.get("newer-game", "1.0.0");

    assertThat(oldest).doesNotExist();
    assertThat(cacheDir.resolve("newer-game_1.0.0.jar")).exists();
  }

  @Test
  void aPinnedEntrySurvivesEvenWhenOverTheSizeLimit() {
    AtomicInteger fetchCount = new AtomicInteger();
    ArtifactCache cache =
        new ArtifactCache(
            fakeStoreWritingBytes(fetchCount, ARTIFACT_SIZE), cacheDir, ARTIFACT_SIZE);

    Path pinned = cache.get("live-game", "1.0.0");
    cache.pin("live-game", "1.0.0");
    cache.get("other-game", "1.0.0");

    assertThat(pinned).exists();
  }
}
