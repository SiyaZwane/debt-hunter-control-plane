package com.debthunter.controlplane.analytics.readmodel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The materialised aggregates analytics queries read. They are derived from the system of record
 * and can be rebuilt from it. <strong>The ingest path never consults this.</strong>
 *
 * <p>Windows are half-open, {@code [from, to)}. Daily counts are whole UTC days.
 */
public interface ReadModelRepository {

  /** How far the projector has read in a source; {@code 0} if it has not started. */
  long checkpoint(String name);

  /** When a projection pass last completed, or empty if one never has. */
  Optional<Instant> lastUpdated();

  /** The open finding with this fingerprint, if there is one. */
  Optional<OpenFinding> openFinding(String repository, String projectSlice, String fingerprint);

  /**
   * Applies a batch atomically and records that a pass completed at {@code now}.
   *
   * @param batch what to write
   * @param now the completion time, which becomes {@link #lastUpdated()}
   */
  void commit(ProjectionBatch batch, Instant now);

  /**
   * The newest snapshot (by commit date) of every project slice of the given repositories.
   *
   * @param repositories the repositories wanted
   * @return one snapshot per repository and slice that has any
   */
  List<RunSnapshot> latestSnapshots(java.util.Collection<String> repositories);

  /** Per-commit snapshots in the window, oldest first. */
  List<RunSnapshot> snapshots(String repository, String projectSlice, Instant from, Instant to);

  /** Per-rule daily counts in the window. */
  List<RuleDelta> ruleDeltas(String repository, String projectSlice, Instant from, Instant to);

  /** Resolutions whose resolution time falls in the window. */
  List<ResolutionRecord> resolutions(
      String repository, String projectSlice, Instant from, Instant to);

  /** Findings that are open now. */
  List<OpenFinding> openFindings(String repository, String projectSlice);

  /** Hotspot observations in the window, oldest first. */
  List<HotspotPoint> hotspots(String repository, String projectSlice, Instant from, Instant to);
}
