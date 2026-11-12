package com.debthunter.controlplane.serve.evidence;

import com.debthunter.controlplane.serve.finding.FindingView;
import com.debthunter.controlplane.store.evidence.EvidenceSummary;
import com.debthunter.controlplane.store.runs.EngineRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything the control plane holds about one run, for investigation. It has no contributor
 * identity, not even as pseudonyms.
 *
 * @param runId the run
 * @param run the run's metadata
 * @param findings the findings it reported
 * @param evidence the evidence retained for it
 * @param policy the policy it was assessed against and the outcome
 * @param generatedAt when this was produced
 */
public record RunEvidence(
    String runId,
    RunMetadata run,
    List<FindingView> findings,
    Evidence evidence,
    Policy policy,
    Instant generatedAt) {

  /** Copies the findings. */
  public RunEvidence {
    findings = List.copyOf(findings);
  }

  /**
   * The run's metadata.
   *
   * @param repository the repository
   * @param projectSlice the slice
   * @param commit the commit analysed
   * @param toolVersion the tool version, or null
   * @param degraded whether an engine failed
   * @param scopeLimited whether it saw only part of the code
   * @param engines each engine and how it fared
   * @param committedAt the commit date, or null
   * @param receivedAt when it was received, or null
   * @param status whether it is still the current run for its commit
   */
  public record RunMetadata(
      String repository,
      String projectSlice,
      String commit,
      String toolVersion,
      boolean degraded,
      boolean scopeLimited,
      List<EngineRecord> engines,
      Instant committedAt,
      Instant receivedAt,
      String status) {

    /** Copies the engines. */
    public RunMetadata {
      engines = List.copyOf(engines);
    }
  }

  /** Which tier the evidence came from. */
  public enum Tier {
    /** Full detail, still in the hot tier. */
    HOT,
    /** Only the daily summaries remain; the detail has aged out. */
    WARM,
    /** Nothing is retained for the run. */
    NONE
  }

  /**
   * The evidence retained for the run.
   *
   * @param tier where it came from
   * @param snapshots the full snapshots (hot tier only)
   * @param summaries the summaries for the day (warm tier only)
   */
  public record Evidence(Tier tier, List<Snapshot> snapshots, List<EvidenceSummary> summaries) {

    /** Copies the lists. */
    public Evidence {
      snapshots = List.copyOf(snapshots);
      summaries = List.copyOf(summaries);
    }
  }

  /**
   * The supporting detail behind one finding at the moment it was observed.
   *
   * @param findingId the finding
   * @param ruleId the rule
   * @param severity how severe
   * @param evidence the retained data
   * @param capturedAt when it was captured
   * @param expiresAt when the detail leaves the hot tier
   */
  public record Snapshot(
      String findingId,
      String ruleId,
      String severity,
      Map<String, Object> evidence,
      Instant capturedAt,
      Instant expiresAt) {

    /** Copies the evidence. */
    public Snapshot {
      evidence = Map.copyOf(evidence);
    }
  }

  /**
   * The policy the run was assessed against.
   *
   * @param known whether the run recorded which bundle it was assessed against; runs received
   *     before this was recorded did not
   * @param bundleVersion the bundle version, or null
   * @param digest the bundle's digest from the catalogue, or null if it is not (or no longer) there
   * @param ratifiedAt when the bundle was ratified, or null
   * @param supersededBy the version that replaced it, or null
   * @param outcome what the run reported ({@code PASSED}, {@code FAILED}, {@code WOULD_FAIL}), or
   *     null; the reasons behind a failure are not retained
   */
  public record Policy(
      boolean known,
      String bundleVersion,
      String digest,
      Instant ratifiedAt,
      String supersededBy,
      String outcome) {}
}
