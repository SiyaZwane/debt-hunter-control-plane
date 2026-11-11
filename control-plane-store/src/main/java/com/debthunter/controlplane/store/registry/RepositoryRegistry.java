package com.debthunter.controlplane.store.registry;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Persistence for onboarded repositories and the workload identities allowed to write for them. */
public interface RepositoryRegistry {

  /** Inserts or replaces a repository. */
  void save(OnboardedRepository repository);

  /** Looks a repository up. */
  Optional<OnboardedRepository> find(String repository);

  /** Removes a repository and every workload grant for it; returns whether it existed. */
  boolean delete(String repository);

  /** Every onboarded repository, in a stable order. */
  List<OnboardedRepository> all();

  /** Allows a workload identity to write for a repository. Idempotent. */
  void grant(String workloadIdentity, String repository);

  /** Withdraws that permission. Idempotent. */
  void revoke(String workloadIdentity, String repository);

  /** Whether a workload identity has been granted a repository. */
  boolean isGranted(String workloadIdentity, String repository);

  /** The repositories a workload identity may write for. */
  Set<String> repositoriesOf(String workloadIdentity);
}
