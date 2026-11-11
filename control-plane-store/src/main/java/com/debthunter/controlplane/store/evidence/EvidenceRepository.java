package com.debthunter.controlplane.store.evidence;

import java.time.Instant;
import java.util.List;

/** Persistence for the two evidence tiers. */
public interface EvidenceRepository {

  /** Stores a snapshot in the hot tier. */
  void saveHot(EvidenceSnapshot snapshot);

  /** The hot-tier snapshots captured in a run. */
  List<EvidenceSnapshot> hotForRun(String runId);

  /**
   * Moves every snapshot expiring at or before {@code asOf} out of the hot tier and into the warm
   * tier's counts, as one atomic step: detail is never lost without being counted, and never
   * counted twice.
   *
   * @return how many snapshots moved
   */
  int moveExpiredToWarm(Instant asOf);

  /** The warm-tier summaries for a repository. */
  List<EvidenceSummary> warmSummaries(String repository);
}
