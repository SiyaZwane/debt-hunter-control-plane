package com.debthunter.controlplane.store.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-23: the registry entry is what drives routing — where a repository's work items go and who
 * may write for it — and it is durable, so it survives a restart.
 */
@Tag("integration")
class CpAc23_RegistryEntryDrivesRoutingTest {

  private static OnboardedRepository widgets(String tracker, String target) {
    return new OnboardedRepository(
        "acme/widgets", "team-a", "tier-1", "1.0", "enforce", new WorkItemRoute(tracker, target));
  }

  private static RepositoryRegistryService service(DataSource dataSource) {
    return new RepositoryRegistryService(new JdbcRepositoryRegistry(dataSource));
  }

  private static DataSource migrated() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return dataSource;
  }

  @Test
  void cpAc23_theEntryDeterminesTheRouteAndTheRouteSurvivesARestart() {
    DataSource dataSource = migrated();
    service(dataSource).onboard(widgets("jira", "WID"));

    RepositoryRegistryService afterRestart = service(dataSource);

    assertThat(afterRestart.routeFor("acme/widgets")).contains(new WorkItemRoute("jira", "WID"));
  }

  @Test
  void cpAc23_editingTheEntryRedirectsWorkItemsWithNoOtherChange() {
    DataSource dataSource = migrated();
    RepositoryRegistryService service = service(dataSource);
    service.onboard(widgets("jira", "WID"));

    service.update(widgets("azure-boards", "WIDGETS"));

    assertThat(service(dataSource).routeFor("acme/widgets"))
        .contains(new WorkItemRoute("azure-boards", "WIDGETS"));
  }

  @Test
  void cpAc23_theRegistryIsAlsoTheAuthorityForWhichWorkloadMayWriteForARepository() {
    DataSource dataSource = migrated();
    RepositoryRegistryService service = service(dataSource);
    service.onboard(widgets("jira", "WID"));
    service.grantWorkload("svc-a", "acme/widgets");

    RepositoryScopeLookup lookup = service(dataSource);

    assertThat(lookup.mayWrite("svc-a", "acme/widgets")).isTrue();
    assertThat(lookup.mayWrite("svc-b", "acme/widgets")).isFalse();
  }

  @Test
  void cpAc23_offboardingRemovesTheRouteAndEveryGrantDurably() {
    DataSource dataSource = migrated();
    RepositoryRegistryService service = service(dataSource);
    service.onboard(widgets("jira", "WID"));
    service.grantWorkload("svc-a", "acme/widgets");

    service.offboard("acme/widgets");

    RepositoryRegistryService afterRestart = service(dataSource);
    assertThat(afterRestart.routeFor("acme/widgets")).isEmpty();
    assertThat(afterRestart.mayWrite("svc-a", "acme/widgets")).isFalse();
  }
}
