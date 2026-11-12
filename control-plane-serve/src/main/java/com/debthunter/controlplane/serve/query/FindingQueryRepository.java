package com.debthunter.controlplane.serve.query;

import com.debthunter.domain.Severity;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Reads findings from the system of record, for the read APIs. */
public interface FindingQueryRepository {

  /**
   * Finds findings, in a stable order (repository, slice, fingerprint).
   *
   * @param filter what to look for
   * @param page which page
   * @return the page
   */
  Page<FindingRow> find(FindingFilter filter, PageRequest page);

  /**
   * Counts the findings that are new, open or regressed, by severity.
   *
   * @param repositories the repositories wanted
   * @return counts by repository; a repository with none may be absent
   */
  Map<String, Map<Severity, Integer>> openCounts(Collection<String> repositories);

  /** When the newest run the data reflects was received, or empty if there is none. */
  Optional<Instant> asOf();
}
