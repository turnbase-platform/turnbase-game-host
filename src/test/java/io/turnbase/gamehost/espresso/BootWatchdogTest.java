package io.turnbase.gamehost.espresso;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A boot that finishes in time is left alone; one that does not is reported exactly once. */
class BootWatchdogTest {

  @Test
  void aBootThatFinishesInTimeNeverFires() throws Exception {
    AtomicInteger fired = new AtomicInteger();
    BootWatchdog watchdog = new BootWatchdog(Duration.ofMillis(200), fired::incrementAndGet);

    assertThat(watchdog.guard("fast", () -> "booted")).isEqualTo("booted");
    Thread.sleep(400);

    assertThat(fired).hasValue(0);
  }

  @Test
  void aBootThatOutlastsTheTimeoutFiresOnceFromAnotherThread() throws Exception {
    CountDownLatch fired = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger count = new AtomicInteger();
    Thread bootThread = Thread.currentThread();
    java.util.concurrent.atomic.AtomicReference<Thread> firedOn =
        new java.util.concurrent.atomic.AtomicReference<>();
    BootWatchdog watchdog =
        new BootWatchdog(
            Duration.ofMillis(100),
            () -> {
              count.incrementAndGet();
              firedOn.set(Thread.currentThread());
              fired.countDown();
            });

    Thread booting =
        new Thread(
            () ->
                watchdog.guard(
                    "slow",
                    () -> {
                      try {
                        release.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return "late";
                    }));
    booting.start();

    assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    booting.join(5000);
    assertThat(count).hasValue(1);
    assertThat(firedOn.get()).isNotSameAs(booting).isNotSameAs(bootThread);
  }

  @Test
  void aDisabledWatchdogJustRunsTheBoot() {
    assertThat(BootWatchdog.disabled().guard("x", () -> 7)).isEqualTo(7);
  }

  /** IMP-M16-04: a queued boot waits for a permit without its timer running. */
  @Test
  void aQueuedBootsTimerStartsOnlyWhenItAcquiresItsPermit() throws Exception {
    AtomicInteger fired = new AtomicInteger();
    BootWatchdog watchdog = new BootWatchdog(Duration.ofMillis(600), fired::incrementAndGet, 1);
    CountDownLatch firstHoldsPermit = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicLong secondStartedAt =
        new java.util.concurrent.atomic.AtomicLong();
    long begin = System.nanoTime();

    Thread first =
        new Thread(
            () ->
                watchdog.guard(
                    "first",
                    () -> {
                      firstHoldsPermit.countDown();
                      sleep(400);
                      return 1;
                    }));
    first.start();
    assertThat(firstHoldsPermit.await(5, TimeUnit.SECONDS)).isTrue();
    // Queued ~0 ms after the first took the permit; runs 400 ms after it, so 800 ms in all: more
    // than
    // the 600 ms timeout if the timer started at the queue, less than it from the permit.
    Thread second =
        new Thread(
            () ->
                watchdog.guard(
                    "second",
                    () -> {
                      secondStartedAt.set(System.nanoTime());
                      sleep(400);
                      return 2;
                    }));
    second.start();
    first.join(5000);
    second.join(5000);

    assertThat(secondStartedAt.get() - begin).isGreaterThan(Duration.ofMillis(350).toNanos());
    assertThat(fired).hasValue(0);
  }

  @Test
  void neverMoreBootsRunThanThePermits() throws Exception {
    BootWatchdog watchdog = new BootWatchdog(Duration.ofSeconds(30), () -> {}, 2);
    AtomicInteger running = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    java.util.List<Thread> threads = new java.util.ArrayList<>();
    for (int i = 0; i < 6; i++) {
      Thread thread =
          new Thread(
              () ->
                  watchdog.guard(
                      "boot",
                      () -> {
                        peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                        sleep(100);
                        running.decrementAndGet();
                        return 0;
                      }));
      threads.add(thread);
      thread.start();
    }
    for (Thread thread : threads) {
      thread.join(10_000);
    }

    assertThat(peak).hasValue(2);
  }

  @Test
  void aBootThatThrowsReturnsItsPermit() {
    BootWatchdog watchdog = new BootWatchdog(Duration.ofSeconds(30), () -> {}, 1);

    for (int i = 0; i < 3; i++) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  watchdog.guard(
                      "bad",
                      () -> {
                        throw new IllegalStateException("boom");
                      }))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(watchdog.guard("good", () -> 1)).isEqualTo(1);
  }

  @Test
  void zeroPermitsAreRejected() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new BootWatchdog(Duration.ofSeconds(1), () -> {}, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
