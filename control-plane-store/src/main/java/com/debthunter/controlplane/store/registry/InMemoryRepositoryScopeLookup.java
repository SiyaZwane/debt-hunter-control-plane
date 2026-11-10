package com.debthunter.controlplane.store.registry;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A {@link RepositoryScopeLookup} over a fixed identity-to-repositories map. Used until the
 * persistent repository registry exists, and as the in-memory double in tests.
 */
public final class InMemoryRepositoryScopeLookup implements RepositoryScopeLookup {

  private final Map<String, Set<String>> repositoriesByIdentity;

  /**
   * Creates a lookup from a snapshot of the given scopes; later changes to the argument have no
   * effect.
   *
   * @param scopes each workload identity mapped to the repositories it may write for
   */
  public InMemoryRepositoryScopeLookup(Map<String, ? extends Collection<String>> scopes) {
    Map<String, Set<String>> copy = new HashMap<>();
    scopes.forEach((identity, repositories) -> copy.put(identity, Set.copyOf(repositories)));
    this.repositoriesByIdentity = Map.copyOf(copy);
  }

  @Override
  public boolean mayWrite(String workloadIdentity, String repository) {
    if (workloadIdentity == null || repository == null) {
      return false;
    }
    Set<String> repositories = repositoriesByIdentity.get(workloadIdentity);
    return repositories != null && repositories.contains(repository);
  }
}
