package com.debthunter.controlplane.store.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * CP-AC-27: a repository the registry has never heard of is not onboarded — it has no route and
 * nobody may write for it. The failure is closed, so a typo in a name cannot conjure a repository.
 */
class CpAc27_UnregisteredRepoIsUnonboardedTest {

  private final RepositoryRegistryService service =
      new RepositoryRegistryService(new InMemoryRepositoryRegistry());

  @Test
  void cpAc27_anUnregisteredRepositoryIsNotOnboarded() {
    assertThat(service.isOnboarded("acme/never-heard-of-it")).isFalse();
    assertThat(service.find("acme/never-heard-of-it")).isEmpty();
  }

  @Test
  void cpAc27_itHasNoRoute() {
    assertThat(service.routeFor("acme/never-heard-of-it")).isEmpty();
  }

  @Test
  void cpAc27_nobodyMayWriteForIt() {
    assertThat(service.mayWrite("svc-a", "acme/never-heard-of-it")).isFalse();
  }

  @Test
  void
      cpAc27_aGrantLeftBehindByTheRawRegistryStillDoesNotLetAnyoneWriteForAnUnregisteredRepository() {
    InMemoryRepositoryRegistry raw = new InMemoryRepositoryRegistry();
    raw.grant("svc-a", "acme/ghost"); // a stale grant with no repository entry behind it

    assertThat(new RepositoryRegistryService(raw).mayWrite("svc-a", "acme/ghost")).isFalse();
  }
}
