package com.debthunter.controlplane.store.lifecycle;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a finding stands in its life, per repository and project slice.
 *
 * <p>The allowed moves are an explicit table ({@link #canMoveTo}), not scattered {@code if}s, so
 * the state machine can be read, tested exhaustively, and cannot be bypassed by a new code path.
 *
 * <pre>
 *  (first observed) -&gt; NEW -&gt; OPEN -&gt; RESOLVED -&gt; REGRESSED -&gt; OPEN
 *                          \-----------&gt; SUPPRESSED -&gt; OPEN (on expiry)
 * </pre>
 */
public enum FindingState {
  /** Observed for the first time. */
  NEW,
  /** Observed again; a standing finding. */
  OPEN,
  /** Absent from a complete run. */
  RESOLVED,
  /** Observed again after having been resolved. */
  REGRESSED,
  /** Deliberately set aside; excluded from gating until the suppression expires. */
  SUPPRESSED;

  /** Whether this state counts against a gate. */
  public boolean blocksGating() {
    return this == NEW || this == OPEN || this == REGRESSED;
  }

  /**
   * Whether the state machine allows moving from this state to another.
   *
   * @param next the target state
   * @return true if the move is in the transition table
   */
  public boolean canMoveTo(FindingState next) {
    return allowedFrom(this).contains(next);
  }

  /**
   * Whether the state machine allows a first observation to start in a state.
   *
   * @param first the state a never-before-seen finding starts in
   * @return true only for {@link #NEW}
   */
  public static boolean canStartIn(FindingState first) {
    return first == NEW;
  }

  private static Set<FindingState> allowedFrom(FindingState from) {
    return switch (from) {
      case NEW -> EnumSet.of(OPEN, RESOLVED, SUPPRESSED);
      case OPEN -> EnumSet.of(RESOLVED, SUPPRESSED);
      case RESOLVED -> EnumSet.of(REGRESSED);
      case REGRESSED -> EnumSet.of(OPEN, RESOLVED, SUPPRESSED);
      case SUPPRESSED -> EnumSet.of(OPEN);
    };
  }
}
