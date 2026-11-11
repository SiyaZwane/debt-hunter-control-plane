package com.debthunter.controlplane.store.registry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The repository registry: which repositories are onboarded, who may write for them, and where
 * their work items go.
 *
 * <p>It is the authority for two decisions made elsewhere. The ingestion edge asks {@link
 * #mayWrite} to decide whether a workload may publish for a repository, and the work-item
 * orchestrator asks {@link #routeFor} where to send a ticket. A repository the registry has never
 * heard of is <em>not onboarded</em>: nobody may write for it and it has no route. That failure is
 * closed, so a typo in a repository name cannot create a phantom repository.
 */
public final class RepositoryRegistryService implements RepositoryScopeLookup {

  private final RepositoryRegistry registry;

  /**
   * Creates the service.
   *
   * @param registry where entries are kept
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public RepositoryRegistryService(RepositoryRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  /**
   * Onboards a repository.
   *
   * @param repository its entry
   * @throws AlreadyOnboardedException if it is already onboarded
   */
  public void onboard(OnboardedRepository repository) {
    if (registry.find(repository.repository()).isPresent()) {
      throw new AlreadyOnboardedException(repository.repository());
    }
    registry.save(repository);
  }

  /**
   * Changes an onboarded repository's entry.
   *
   * @param repository its new entry
   * @throws NotOnboardedException if it is not onboarded
   */
  public void update(OnboardedRepository repository) {
    requireOnboarded(repository.repository());
    registry.save(repository);
  }

  /**
   * Offboards a repository, withdrawing every workload's permission to write for it.
   *
   * @param repository the repository
   * @return whether it was onboarded
   */
  public boolean offboard(String repository) {
    return registry.delete(repository);
  }

  /**
   * Looks a repository up.
   *
   * @param repository the repository
   * @return its entry, or empty if it is not onboarded
   */
  public Optional<OnboardedRepository> find(String repository) {
    return registry.find(repository);
  }

  /** Every onboarded repository. */
  public List<OnboardedRepository> all() {
    return registry.all();
  }

  /**
   * Whether the control plane knows this repository.
   *
   * @param repository the repository
   * @return true if it is onboarded
   */
  public boolean isOnboarded(String repository) {
    return registry.find(repository).isPresent();
  }

  /**
   * Where a repository's work items go.
   *
   * @param repository the repository
   * @return its route, or empty if it is not onboarded
   */
  public Optional<WorkItemRoute> routeFor(String repository) {
    return registry.find(repository).map(OnboardedRepository::route);
  }

  /**
   * Allows a workload identity to write for an onboarded repository.
   *
   * @param workloadIdentity the identity
   * @param repository the repository
   * @throws NotOnboardedException if the repository is not onboarded
   */
  public void grantWorkload(String workloadIdentity, String repository) {
    requireOnboarded(repository);
    registry.grant(workloadIdentity, repository);
  }

  /**
   * The repositories a workload identity has been granted.
   *
   * @param workloadIdentity the identity
   * @return the repositories, which may include ones since offboarded only if a grant was left
   *     behind by something other than this service
   */
  public java.util.Set<String> repositoriesOf(String workloadIdentity) {
    return registry.repositoriesOf(workloadIdentity);
  }

  /**
   * Withdraws a workload identity's permission to write for a repository.
   *
   * @param workloadIdentity the identity
   * @param repository the repository
   */
  public void revokeWorkload(String workloadIdentity, String repository) {
    registry.revoke(workloadIdentity, repository);
  }

  @Override
  public boolean mayWrite(String workloadIdentity, String repository) {
    return registry.find(repository).isPresent()
        && registry.isGranted(workloadIdentity, repository);
  }

  private void requireOnboarded(String repository) {
    if (registry.find(repository).isEmpty()) {
      throw new NotOnboardedException(repository);
    }
  }
}
