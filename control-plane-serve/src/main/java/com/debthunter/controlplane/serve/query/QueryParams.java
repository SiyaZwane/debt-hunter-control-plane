package com.debthunter.controlplane.serve.query;

import com.debthunter.controlplane.serve.access.InvalidRequestException;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Instant;
import java.util.Set;

/**
 * What a caller asks for, before it is limited to what they may see.
 *
 * @param repository only this repository, or null for all the caller may see
 * @param severity only this severity, or null
 * @param category only this category, or null
 * @param state only findings in this state, or null
 * @param observedFrom only findings whose latest run was committed at or after this, or null
 * @param observedTo only findings whose latest run was committed before this, or null
 * @param limit page size, or null for the default
 * @param cursor where the previous page stopped, or null
 */
public record QueryParams(
    String repository,
    Severity severity,
    Category category,
    FindingState state,
    Instant observedFrom,
    Instant observedTo,
    Integer limit,
    String cursor) {

  /** Validates that the window is the right way round. */
  public QueryParams {
    if (observedFrom != null && observedTo != null && !observedFrom.isBefore(observedTo)) {
      throw new InvalidRequestException("The time window must end after it starts");
    }
  }

  /** No filters, the first default-sized page. */
  public static QueryParams all() {
    return new QueryParams(null, null, null, null, null, null, null, null);
  }

  /** The filter over the repositories the caller may see. */
  public FindingFilter toFilter(Set<String> allowed) {
    return new FindingFilter(allowed, severity, category, state, observedFrom, observedTo);
  }

  /** The requested page. */
  public PageRequest page() {
    return PageRequest.of(limit, cursor);
  }
}
