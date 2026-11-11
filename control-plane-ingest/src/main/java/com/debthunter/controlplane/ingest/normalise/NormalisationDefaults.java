package com.debthunter.controlplane.ingest.normalise;

import java.util.Map;

/**
 * The documented values used for a field the current schema has but an older bundle does not. Every
 * use of one is recorded in the bundle's {@link NormalisationReport}, so a consumer can always tell
 * a reported value from a defaulted one.
 */
public final class NormalisationDefaults {

  /**
   * A finding with no stated confidence. Findings from before confidence existed came from
   * deterministic engines, which have no notion of "unsure", so they are taken at full confidence.
   */
  public static final double CONFIDENCE = 1.0;

  /** A finding with no stated score: unscored, not low-scored. */
  public static final double SCORE = 0.0;

  /**
   * A finding with no stated newness. {@code false} claims nothing; whether a finding is new is
   * decided afresh by the lifecycle state machine from its fingerprint.
   */
  public static final boolean IS_NEW = false;

  /** A finding with no evidence. */
  public static final Map<String, Object> EVIDENCE = Map.of();

  /** A policy outcome that does not say which policy bundle produced it. */
  public static final String POLICY_BUNDLE_VERSION = "unknown";

  private NormalisationDefaults() {}
}
