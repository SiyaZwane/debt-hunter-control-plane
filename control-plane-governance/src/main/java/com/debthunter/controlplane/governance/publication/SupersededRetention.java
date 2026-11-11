package com.debthunter.controlplane.governance.publication;

/**
 * How long a superseded policy bundle must stay available: two major tool version cycles, because a
 * pipeline pinned to an older tool major may legitimately still be pulling the bundle that was
 * current when that tool shipped. Registry-side retention jobs consult this before removing
 * anything; this service never removes anything itself.
 */
public final class SupersededRetention {

  /** The number of major tool version cycles a superseded bundle is kept for. */
  public static final int MAJOR_CYCLES = 2;

  private SupersededRetention() {}

  /**
   * Whether a superseded bundle must still be kept.
   *
   * @param toolMajorWhenSuperseded the major tool version current when the bundle was superseded
   * @param currentToolMajor the major tool version current now
   * @return true while fewer than {@link #MAJOR_CYCLES} major cycles have completed since
   */
  public static boolean mustRetain(int toolMajorWhenSuperseded, int currentToolMajor) {
    return currentToolMajor - toolMajorWhenSuperseded <= MAJOR_CYCLES;
  }
}
