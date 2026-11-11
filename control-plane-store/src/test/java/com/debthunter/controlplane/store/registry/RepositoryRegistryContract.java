package com.debthunter.controlplane.store.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class RepositoryRegistryContract {

  protected abstract RepositoryRegistry newRegistry();

  private RepositoryRegistry registry;

  @BeforeEach
  void create() {
    registry = newRegistry();
  }

  static OnboardedRepository widgets() {
    return new OnboardedRepository(
        "acme/widgets", "team-a", "tier-1", "1.0", "enforce", new WorkItemRoute("jira", "WID"));
  }

  @Test
  void aSavedRepositoryReadsBackWithEveryField() {
    registry.save(widgets());

    assertThat(registry.find("acme/widgets")).contains(widgets());
  }

  @Test
  void savingAgainReplacesTheEntry() {
    registry.save(widgets());
    registry.save(
        new OnboardedRepository(
            "acme/widgets", "team-b", "tier-2", "2.0", "warn", new WorkItemRoute("ado", "BOARD")));

    assertThat(registry.find("acme/widgets").get().owner()).isEqualTo("team-b");
    assertThat(registry.all()).hasSize(1);
  }

  @Test
  void anUnknownRepositoryIsAbsent() {
    assertThat(registry.find("nope/nope")).isEmpty();
  }

  @Test
  void deletingARepositoryAlsoWithdrawsItsWorkloadGrants() {
    registry.save(widgets());
    registry.grant("svc-a", "acme/widgets");

    assertThat(registry.delete("acme/widgets")).isTrue();

    assertThat(registry.find("acme/widgets")).isEmpty();
    assertThat(registry.isGranted("svc-a", "acme/widgets")).isFalse();
    assertThat(registry.delete("acme/widgets")).isFalse();
  }

  @Test
  void grantsAreIdempotentAndRevocable() {
    registry.save(widgets());
    registry.grant("svc-a", "acme/widgets");
    registry.grant("svc-a", "acme/widgets");

    assertThat(registry.isGranted("svc-a", "acme/widgets")).isTrue();
    assertThat(registry.repositoriesOf("svc-a")).containsExactly("acme/widgets");

    registry.revoke("svc-a", "acme/widgets");
    assertThat(registry.isGranted("svc-a", "acme/widgets")).isFalse();
  }

  @Test
  void aGrantIsPerWorkloadAndPerRepository() {
    registry.save(widgets());
    registry.grant("svc-a", "acme/widgets");

    assertThat(registry.isGranted("svc-b", "acme/widgets")).isFalse();
    assertThat(registry.isGranted("svc-a", "acme/other")).isFalse();
    assertThat(registry.repositoriesOf("svc-b")).isEmpty();
  }

  @Test
  void allListsRepositoriesInAStableOrder() {
    registry.save(new OnboardedRepository("z/z", "o", "t", "1", "s", new WorkItemRoute("a", "b")));
    registry.save(new OnboardedRepository("a/a", "o", "t", "1", "s", new WorkItemRoute("a", "b")));

    assertThat(registry.all())
        .extracting(OnboardedRepository::repository)
        .containsExactly("a/a", "z/z");
  }
}
