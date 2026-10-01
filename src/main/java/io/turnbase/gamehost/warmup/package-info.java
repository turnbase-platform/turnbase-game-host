/**
 * Pre-readiness warm-up (IMP-M7-09): a synthetic session through a throwaway Espresso context,
 * entirely outside {@code GameContextManager}, the session record store and registration, so it can
 * never surface in history, statistics or the active-session gauge.
 */
@NullMarked
package io.turnbase.gamehost.warmup;

import org.jspecify.annotations.NullMarked;
