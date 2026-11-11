package com.debthunter.controlplane.store.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** A {@link RepositoryRegistry} held in memory, for tests and local development. */
public final class InMemoryRepositoryRegistry implements RepositoryRegistry {

  private final Map<String, OnboardedRepository> repositories = new TreeMap<>();
  private final Set<String> grants = new HashSet<>();

  private static String key(String workload, String repository) {
    return workload + '\u0000' + repository;
  }

  @Override
  public synchronized void save(OnboardedRepository repository) {
    repositories.put(repository.repository(), repository);
  }

  @Override
  public synchronized Optional<OnboardedRepository> find(String repository) {
    return Optional.ofNullable(repositories.get(repository));
  }

  @Override
  public synchronized boolean delete(String repository) {
    grants.removeIf(g -> g.endsWith('\u0000' + repository));
    return repositories.remove(repository) != null;
  }

  @Override
  public synchronized List<OnboardedRepository> all() {
    List<OnboardedRepository> all = new ArrayList<>(repositories.values());
    all.sort(Comparator.comparing(OnboardedRepository::repository));
    return all;
  }

  @Override
  public synchronized void grant(String workloadIdentity, String repository) {
    grants.add(key(workloadIdentity, repository));
  }

  @Override
  public synchronized void revoke(String workloadIdentity, String repository) {
    grants.remove(key(workloadIdentity, repository));
  }

  @Override
  public synchronized boolean isGranted(String workloadIdentity, String repository) {
    return grants.contains(key(workloadIdentity, repository));
  }

  @Override
  public synchronized Set<String> repositoriesOf(String workloadIdentity) {
    Set<String> result = new HashSet<>();
    String prefix = workloadIdentity + '\u0000';
    for (String grant : grants) {
      if (grant.startsWith(prefix)) {
        result.add(grant.substring(prefix.length()));
      }
    }
    return Set.copyOf(result);
  }
}
