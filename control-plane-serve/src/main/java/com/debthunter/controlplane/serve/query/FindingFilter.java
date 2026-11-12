package com.debthunter.controlplane.serve.query;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * What a query asks for. The repositories are always given and are the most the query may touch;
 * every other field narrows.
 *
 * @param repositories the repositories to look in; never empty
 * @param severity only this severity, or null
 * @param category only this category, or null
 * @param state only findings in this state, or null
 * @param observedFrom only findings whose latest run was committed at or after this, or null
 * @param observedTo only findings whose latest run was committed before this, or null
 */
public record FindingFilter(
    Set<String> repositories,
    Severity severity,
    Category category,
    FindingState state,
    Instant observedFrom,
    Instant observedTo) {

  /** Validates and copies the components. */
  public FindingFilter {
    Objects.requireNonNull(repositories, "repositories");
    if (repositories.isEmpty()) {
      throw new IllegalArgumentException("A query needs at least one repository to look in");
    }
    repositories = Set.copyOf(repositories);
  }
}
