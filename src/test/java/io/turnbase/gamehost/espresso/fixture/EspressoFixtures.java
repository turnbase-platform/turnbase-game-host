package io.turnbase.gamehost.espresso.fixture;

/**
 * A tiny guest-side program, compiled by the normal test build to {@code target/test-classes} —
 * used as the "game JAR" directory entry on {@code java.Classpath} in isolation tests. Each
 * Espresso {@code Context} loads its own copy of this class with its own static state
 * (FR-CTX-001/003), which is exactly the mechanism these tests exist to prove.
 */
public final class EspressoFixtures {

  private static int counter = 0;

  private EspressoFixtures() {}

  public static int incrementAndGet() {
    return ++counter;
  }

  public static void exitWithCode(int code) {
    System.exit(code);
  }

  public static void spinForever() {
    while (true) {
      // FR-CTX-008: deliberately uninterruptible by cooperative means — only Context.interrupt
      // / Context.close(true) should be able to stop this.
    }
  }

  public static String javaSpecVersion() {
    return System.getProperty("java.specification.version");
  }
}
