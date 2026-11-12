package com.debthunter.controlplane.serve.query;

import com.debthunter.controlplane.serve.access.Access;
import com.debthunter.controlplane.serve.access.AccessDeniedException;
import com.debthunter.controlplane.serve.access.Caller;
import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The programmatic read of findings. A caller sees the findings of the repositories owned by the
 * squads in their token and nothing else; asking for a repository outside them is refused, whether
 * or not it exists.
 */
public final class QueryService {

  private final FindingQueryRepository findings;
  private final RepositoryRegistry registry;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param findings where findings are read from
   * @param registry which squad owns which repository
   * @param clock the source of "now"
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public QueryService(FindingQueryRepository findings, RepositoryRegistry registry, Clock clock) {
    this.findings = Objects.requireNonNull(findings, "findings");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Finds findings.
   *
   * @param caller who is asking
   * @param params what they ask for
   * @return a page, with when the data is as of
   * @throws AccessDeniedException if the caller lacks the squad role or names a repository outside
   *     their squads
   */
  public QueryResult query(Caller caller, QueryParams params) {
    Access.requireRole(caller, Roles.SQUAD);
    Set<String> allowed =
        registry.all().stream()
            .filter(r -> caller.isIn(r.owner()))
            .map(OnboardedRepository::repository)
            .collect(Collectors.toSet());
    if (params.repository() != null) {
      if (!allowed.contains(params.repository())) {
        throw new AccessDeniedException("The caller may not see that repository");
      }
      allowed = Set.of(params.repository());
    }
    if (allowed.isEmpty()) {
      return new QueryResult(
          java.util.List.of(), null, findings.asOf().orElse(null), clock.instant());
    }
    Page<FindingRow> page = findings.find(params.toFilter(allowed), params.page());
    return new QueryResult(
        page.items(), page.nextCursor(), findings.asOf().orElse(null), clock.instant());
  }
}
