package io.turnbase.gamehost.espresso.bench;

import io.turnbase.gamehost.espresso.EspressoContextFactory;
import io.turnbase.gamehost.shim.GameBridgeClient;
import java.lang.management.ManagementFactory;
import java.util.UUID;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;

/**
 * IMP-M16-05: boots {@code contexts} Espresso contexts and plays {@code moves} actions in each,
 * then prints one line of {@code key=value} measurements. Run as a child JVM by {@code
 * EngineSharingBenchmarkIT}, once per mode, so neither mode inherits the other's warm JIT, loaded
 * Espresso runtime or heap.
 *
 * <p>Arguments: {@code shared|private} {@code contexts} {@code moves}. {@code java.class.path} is
 * the guest classpath, as in {@code ConcurrentBootsIT}.
 */
public final class EngineSharingBenchmark {

  private static final String ENTRY_POINT_FQCN = "io.turnbase.gamehost.bridge.fixture.CountingGame";

  private EngineSharingBenchmark() {}

  public static void main(String[] args) {
    boolean shared = args[0].equals("shared");
    int contexts = Integer.parseInt(args[1]);
    int moves = Integer.parseInt(args[2]);

    com.sun.management.OperatingSystemMXBean os =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    long cpuBefore = os.getProcessCpuTime();
    long wallBefore = System.nanoTime();

    Engine engine = shared ? Engine.newBuilder().allowExperimentalOptions(true).build() : null;
    EspressoContextFactory factory =
        new EspressoContextFactory(
            System.getProperty("java.home"), System.getProperty("java.class.path"), engine);
    Context[] opened = new Context[contexts];
    String[] sessions = new String[contexts];
    GameBridgeClient[] clients = new GameBridgeClient[contexts];
    UUID playerId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    String player =
        "{\"playerId\":\"%s\",\"displayName\":\"Alice\",\"teamId\":\"%s\"}"
            .formatted(playerId, teamId);
    String teams =
        "[{\"teamId\":\"%s\",\"name\":\"Team A\",\"players\":[%s]}]".formatted(teamId, player);
    for (int i = 0; i < contexts; i++) {
      opened[i] = factory.createBridgeOnly();
      clients[i] = new GameBridgeClient(opened[i]);
      sessions[i] = UUID.randomUUID().toString();
      clients[i].createSession(sessions[i], ENTRY_POINT_FQCN, teams, "{\"start\":0}");
    }
    for (int move = 0; move < moves; move++) {
      for (int i = 0; i < contexts; i++) {
        String envelope =
            clients[i].processAction(sessions[i], "{\"count\":0}", player, "{\"by\":1}");
        if (!envelope.contains("\"status\":\"OK\"")) {
          throw new IllegalStateException("move failed: " + envelope);
        }
      }
    }

    long wallMillis = (System.nanoTime() - wallBefore) / 1_000_000;
    long cpuMillis = (os.getProcessCpuTime() - cpuBefore) / 1_000_000;
    for (int i = 0; i < 3; i++) {
      System.gc();
    }
    Runtime runtime = Runtime.getRuntime();
    long heapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    System.out.println(
        "RESULT mode=%s contexts=%d moves=%d cpuMillis=%d wallMillis=%d heapMb=%d"
            .formatted(args[0], contexts, moves, cpuMillis, wallMillis, heapMb));
    for (Context context : opened) {
      context.close(true);
    }
    if (engine != null) {
      engine.close(true);
    }
  }
}
