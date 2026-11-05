package io.turnbase.gamehost.espresso.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * IMP-M16-05, the owner's acceptance rule: a shared {@code Engine} is adopted only if four contexts
 * of distinct {@code gameId:version}, each playing the same moves, cost at least 25% less CPU time
 * or at least 25% less heap after GC than four contexts with an engine each. Each mode runs in a
 * child JVM (see {@link EngineSharingBenchmark}); the numbers are printed so the decision record
 * (ADR-0019) can quote them, and the test fails if the rule stops holding.
 */
@Tag("perf")
class EngineSharingBenchmarkIT {

  private static final int CONTEXTS = 4;
  private static final int MOVES = 100;
  private static final double REQUIRED_SAVING = 0.25;

  @Test
  void sharedEngineSavesAtLeastAQuarterOfCpuOrHeap() throws Exception {
    Map<String, Long> privateEngines = run("private");
    Map<String, Long> sharedEngine = run("shared");

    double cpuSaving = saving(privateEngines, sharedEngine, "cpuMillis");
    double heapSaving = saving(privateEngines, sharedEngine, "heapMb");
    System.out.printf(
        "IMP-M16-05 benchmark: private=%s shared=%s cpuSaving=%.1f%% heapSaving=%.1f%%%n",
        privateEngines, sharedEngine, cpuSaving * 100, heapSaving * 100);

    assertThat(Math.max(cpuSaving, heapSaving)).isGreaterThanOrEqualTo(REQUIRED_SAVING);
  }

  private static double saving(
      Map<String, Long> baseline, Map<String, Long> candidate, String metric) {
    return 1.0 - (double) candidate.getOrDefault(metric, 0L) / baseline.getOrDefault(metric, 1L);
  }

  private static Map<String, Long> run(String mode) throws IOException, InterruptedException {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Process process =
        new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                EngineSharingBenchmark.class.getName(),
                mode,
                Integer.toString(CONTEXTS),
                Integer.toString(MOVES))
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(5, TimeUnit.MINUTES)).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();

    Map<String, Long> result = new HashMap<>();
    for (String line : output.split("\n")) {
      if (line.startsWith("RESULT ")) {
        for (String pair : line.substring("RESULT ".length()).split(" ")) {
          String[] kv = pair.split("=");
          if (kv[1].chars().allMatch(Character::isDigit)) {
            result.put(kv[0], Long.parseLong(kv[1]));
          }
        }
      }
    }
    assertThat(result).as(output).containsKeys("cpuMillis", "heapMb");
    return result;
  }
}
