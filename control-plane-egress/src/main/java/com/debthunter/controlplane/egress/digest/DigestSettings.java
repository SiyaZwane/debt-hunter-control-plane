package com.debthunter.controlplane.egress.digest;

import java.time.Duration;
import java.util.Objects;

/**
 * How digests are made.
 *
 * @param cadence the length of a period; periods start at multiples of it from the Unix epoch, so a
 *     daily cadence closes at 00:00 UTC
 * @param expiryHorizon how far ahead a suppression's end is worth mentioning
 * @param maxNotable the most findings listed by name
 * @param maxStages the most repositories listed with their stage
 * @param maxText the longest any text from a repository may be
 * @param sendEmpty whether a period in which nothing changed still produces a digest
 */
public record DigestSettings(
    Duration cadence,
    Duration expiryHorizon,
    int maxNotable,
    int maxStages,
    int maxText,
    boolean sendEmpty) {

  /** Validates the components. */
  public DigestSettings {
    Objects.requireNonNull(cadence, "cadence");
    Objects.requireNonNull(expiryHorizon, "expiryHorizon");
    if (cadence.compareTo(Duration.ofMinutes(1)) < 0) {
      throw new IllegalArgumentException("cadence must be at least a minute");
    }
    if (expiryHorizon.isNegative() || expiryHorizon.isZero()) {
      throw new IllegalArgumentException("expiryHorizon must be positive");
    }
    if (maxNotable < 0 || maxStages < 1 || maxText < 20) {
      throw new IllegalArgumentException("limits are out of range");
    }
  }

  /** Daily, a seven-day look ahead, five findings by name, ten repositories, empty skipped. */
  public static DigestSettings defaults() {
    return new DigestSettings(Duration.ofDays(1), Duration.ofDays(7), 5, 10, 200, false);
  }
}
