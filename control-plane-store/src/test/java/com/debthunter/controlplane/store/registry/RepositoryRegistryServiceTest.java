package com.debthunter.controlplane.store.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RepositoryRegistryServiceTest {

  private final RepositoryRegistryService service =
      new RepositoryRegistryService(new InMemoryRepositoryRegistry());

  private static OnboardedRepository repo(String name, String tracker, String target) {
    return new OnboardedRepository(
        name, "team-a", "tier-1", "1.0", "enforce", new WorkItemRoute(tracker, target));
  }

  @Test
  void anOnboardedRepositoryCanBeFoundListedAndRouted() {
    service.onboard(repo("acme/widgets", "jira", "WID"));

    assertThat(service.find("acme/widgets")).isPresent();
    assertThat(service.all()).hasSize(1);
    assertThat(service.routeFor("acme/widgets")).contains(new WorkItemRoute("jira", "WID"));
  }

  @Test
  void cpAc23_changingTheEntryChangesWhereWorkItemsGo() {
    service.onboard(repo("acme/widgets", "jira", "WID"));

    service.update(repo("acme/widgets", "azure-boards", "WIDGETS-BOARD"));

    assertThat(service.routeFor("acme/widgets"))
        .contains(new WorkItemRoute("azure-boards", "WIDGETS-BOARD"));
  }

  @Test
  void onboardingTwiceIsRefusedRatherThanSilentlyOverwriting() {
    service.onboard(repo("acme/widgets", "jira", "WID"));

    assertThatThrownBy(() -> service.onboard(repo("acme/widgets", "jira", "OTHER")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(service.routeFor("acme/widgets")).contains(new WorkItemRoute("jira", "WID"));
  }

  @Test
  void updatingARepositoryThatIsNotOnboardedIsRefused() {
    assertThatThrownBy(() -> service.update(repo("acme/ghost", "jira", "G")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aWorkloadGrantedARepositoryMayWriteForIt() {
    service.onboard(repo("acme/widgets", "jira", "WID"));
    service.grantWorkload("svc-a", "acme/widgets");

    assertThat(service.mayWrite("svc-a", "acme/widgets")).isTrue();
    assertThat(service.mayWrite("svc-b", "acme/widgets")).isFalse();
  }

  @Test
  void aGrantForARepositoryThatIsNotOnboardedIsRefused() {
    assertThatThrownBy(() -> service.grantWorkload("svc-a", "acme/ghost"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void revokingWithdrawsThePermission() {
    service.onboard(repo("acme/widgets", "jira", "WID"));
    service.grantWorkload("svc-a", "acme/widgets");

    service.revokeWorkload("svc-a", "acme/widgets");

    assertThat(service.mayWrite("svc-a", "acme/widgets")).isFalse();
  }

  @Test
  void offboardingRemovesTheRouteAndEveryWorkloadsPermission() {
    service.onboard(repo("acme/widgets", "jira", "WID"));
    service.grantWorkload("svc-a", "acme/widgets");

    assertThat(service.offboard("acme/widgets")).isTrue();

    assertThat(service.isOnboarded("acme/widgets")).isFalse();
    assertThat(service.routeFor("acme/widgets")).isEmpty();
    assertThat(service.mayWrite("svc-a", "acme/widgets")).isFalse();
  }

  @Test
  void anEntryMissingAnyFieldCannotBeConstructed() {
    assertThatThrownBy(
            () -> new OnboardedRepository("a/b", "", "t", "1", "s", new WorkItemRoute("x", "y")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WorkItemRoute(" ", "y"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
