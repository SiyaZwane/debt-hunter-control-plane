package com.debthunter.controlplane.governance.simulation;

import java.time.Duration;

/**
 * How far back to replay and how much to replay per repository.
 *
 * @param lookback how far back from now a run may be and still count
 * @param maxRunsPerRepository the most runs replayed for one repository; bounds the cost of a
 *     simulation over a large estate
 */
public record SimulationRequest(Duration lookback, int maxRunsPerRepository) {

  /** The default lookback window. */
  public static final Duration DEFAULT_LOOKBACK = Duration.ofDays(90);

  /** The longest lookback accepted. */
  public static final Duration MAX_LOOKBACK = Duration.ofDays(730);

  /** The default number of runs replayed per repository. */
  public static final int DEFAULT_MAX_RUNS = 30;

  /** The most runs per repository that can be asked for. */
  public static final int MAX_RUNS_LIMIT = 500;

  /** Rejects a lookback or a run bound outside what a simulation can sensibly do. */
  public SimulationRequest {
    if (lookback == null || lookback.isZero() || lookback.isNegative()) {
      throw new IllegalArgumentException("lookback must be a positive duration: " + lookback);
    }
    if (lookback.compareTo(MAX_LOOKBACK) > 0) {
      throw new IllegalArgumentException(
          "lookback must be at most " + MAX_LOOKBACK.toDays() + " days: " + lookback);
    }
    if (maxRunsPerRepository < 1 || maxRunsPerRepository > MAX_RUNS_LIMIT) {
      throw new IllegalArgumentException(
          "maxRunsPerRepository must be between 1 and "
              + MAX_RUNS_LIMIT
              + ": "
              + maxRunsPerRepository);
    }
  }

  /**
   * The default request: ninety days, thirty runs per repository.
   *
   * @return the default request
   */
  public static SimulationRequest defaults() {
    return new SimulationRequest(DEFAULT_LOOKBACK, DEFAULT_MAX_RUNS);
  }

  /**
   * The default run bound over a chosen lookback.
   *
   * @param lookback how far back to replay
   * @return the request
   */
  public static SimulationRequest over(Duration lookback) {
    return new SimulationRequest(lookback, DEFAULT_MAX_RUNS);
  }
}
