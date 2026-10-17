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
}
