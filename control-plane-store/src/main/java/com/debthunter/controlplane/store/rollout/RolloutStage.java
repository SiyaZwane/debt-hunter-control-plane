package com.debthunter.controlplane.store.rollout;

/**
 * How far a repository has progressed through adoption. Stages are strictly ordered and a
 * repository moves one stage forward at a time.
 *
 * <p>What the control plane itself does differently per stage is deliberately small: at stage 1 the
 * data plane only observes, and work items are created from {@link #WORK_ITEM_THRESHOLD} upwards.
 * Everything else about a stage is policy that lives with the people who run the rollout.
 */
public enum RolloutStage {
  /** Observe only. */
  STAGE_1(1),
  /** Findings are visible and acted on locally, but no work items are created. */
  STAGE_2(2),
  /** Work items are created for qualifying findings. */
  STAGE_3(3),
  /** The final stage. */
  STAGE_4(4);

  /** The first stage at which work items are created. */
  public static final RolloutStage WORK_ITEM_THRESHOLD = STAGE_3;

  private final int level;

  RolloutStage(int level) {
    this.level = level;
  }

  /** The stage's number, 1 to 4. */
  public int level() {
    return level;
  }

  /** Whether work items are created at this stage. */
  public boolean createsWorkItems() {
    return level >= WORK_ITEM_THRESHOLD.level;
  }

  /** The stage after this one, or {@code null} at the last. */
  public RolloutStage next() {
    return level == values().length ? null : values()[level];
  }

  /**
   * Looks a stage up by number.
   *
   * @param level 1 to 4
   * @return the stage
   * @throws IllegalArgumentException if there is no such stage
   */
  public static RolloutStage of(int level) {
    if (level < 1 || level > values().length) {
      throw new IllegalArgumentException("No rollout stage " + level);
    }
    return values()[level - 1];
  }
}
