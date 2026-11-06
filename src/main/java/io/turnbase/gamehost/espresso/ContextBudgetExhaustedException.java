package io.turnbase.gamehost.espresso;

/** This JVM has created all the Espresso contexts it can (see {@link ContextBudget}). */
public final class ContextBudgetExhaustedException extends RuntimeException {

  public ContextBudgetExhaustedException(int used) {
    super("Game Host is retiring: all " + used + " guest contexts of this JVM are spent");
  }
}
