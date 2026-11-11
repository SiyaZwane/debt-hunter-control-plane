package com.debthunter.controlplane.analytics.readmodel;

import java.util.List;
import java.util.Map;

/**
 * Everything one projection step writes, applied as a single atomic unit together with the
 * checkpoints it advances, so a batch is never half-applied and never applied twice.
 *
 * @param checkpoints the new position in each source that was read
 * @param ruleDeltas increments to add to the per-rule daily counts
 * @param snapshots per-commit snapshots, replacing any for the same commit
 * @param hotspots hotspot observations for those commits, replacing any for them
 * @param closed open findings that were resolved (deleted before {@code opened} is applied)
 * @param opened findings that are open at the end of the batch
 * @param resolutions completed resolutions
 */
public record ProjectionBatch(
    Map<String, Long> checkpoints,
    List<RuleDelta> ruleDeltas,
    List<RunSnapshot> snapshots,
    List<HotspotPoint> hotspots,
    List<OpenFinding> closed,
    List<OpenFinding> opened,
    List<ResolutionRecord> resolutions) {

  /** Defensively copies the collections. */
  public ProjectionBatch {
    checkpoints = Map.copyOf(checkpoints);
    ruleDeltas = List.copyOf(ruleDeltas);
    snapshots = List.copyOf(snapshots);
    hotspots = List.copyOf(hotspots);
    closed = List.copyOf(closed);
    opened = List.copyOf(opened);
    resolutions = List.copyOf(resolutions);
  }

  /** A batch that changes nothing but the heartbeat. */
  public static ProjectionBatch empty() {
    return new ProjectionBatch(
        Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }
}
