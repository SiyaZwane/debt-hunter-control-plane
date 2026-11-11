package com.debthunter.controlplane.store.evidence;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The supporting detail behind a finding at the moment it was observed.
 *
 * <p>It can only be captured from a {@link PseudonymisedFinding}, so the evidence it holds has
 * already been through pseudonymisation and the allow-list of identity-free keys.
 */
public final class EvidenceSnapshot {

  private final String id;
  private final String runId;
  private final String repository;
  private final String findingId;
  private final String fingerprint;
  private final String ruleId;
  private final String severity;
  private final Map<String, Object> evidence;
  private final Instant capturedAt;
  private final Instant expiresAt;

  private EvidenceSnapshot(
      String id,
      String runId,
      String repository,
      String findingId,
      String fingerprint,
      String ruleId,
      String severity,
      Map<String, Object> evidence,
      Instant capturedAt,
      Instant expiresAt) {
    this.id = Objects.requireNonNull(id);
    this.runId = Objects.requireNonNull(runId);
    this.repository = Objects.requireNonNull(repository);
    this.findingId = Objects.requireNonNull(findingId);
    this.fingerprint = Objects.requireNonNull(fingerprint);
    this.ruleId = Objects.requireNonNull(ruleId);
    this.severity = Objects.requireNonNull(severity);
    this.evidence = Map.copyOf(evidence);
    this.capturedAt = Objects.requireNonNull(capturedAt);
    this.expiresAt = Objects.requireNonNull(expiresAt);
  }

  /**
   * Captures a finding's evidence.
   *
   * @param runId the run
   * @param repository the repository
   * @param finding the pseudonymised finding
   * @param capturedAt when (the commit date)
   * @param ttl how long the detail is kept in the hot tier
   * @return the snapshot
   */
  public static EvidenceSnapshot capture(
      String runId,
      String repository,
      PseudonymisedFinding finding,
      Instant capturedAt,
      Duration ttl) {
    return new EvidenceSnapshot(
        UUID.randomUUID().toString(),
        runId,
        repository,
        finding.id(),
        finding.fingerprint(),
        finding.ruleId(),
        finding.severity().name(),
        finding.evidence(),
        capturedAt,
        capturedAt.plus(ttl));
  }

  /** Rebuilds a snapshot read back from storage. For repository implementations only. */
  public static EvidenceSnapshot restore(
      String id,
      String runId,
      String repository,
      String findingId,
      String fingerprint,
      String ruleId,
      String severity,
      Map<String, Object> evidence,
      Instant capturedAt,
      Instant expiresAt) {
    return new EvidenceSnapshot(
        id,
        runId,
        repository,
        findingId,
        fingerprint,
        ruleId,
        severity,
        evidence,
        capturedAt,
        expiresAt);
  }

  /** The snapshot's id. */
  public String id() {
    return id;
  }

  /** The run it was captured in. */
  public String runId() {
    return runId;
  }

  /** The repository. */
  public String repository() {
    return repository;
  }

  /** The finding's id. */
  public String findingId() {
    return findingId;
  }

  /** The finding's fingerprint. */
  public String fingerprint() {
    return fingerprint;
  }

  /** The rule that raised the finding. */
  public String ruleId() {
    return ruleId;
  }

  /** The finding's severity. */
  public String severity() {
    return severity;
  }

  /** The identity-free evidence. */
  public Map<String, Object> evidence() {
    return evidence;
  }

  /** When it was captured. */
  public Instant capturedAt() {
    return capturedAt;
  }

  /** When the hot-tier detail expires. */
  public Instant expiresAt() {
    return expiresAt;
  }
}
