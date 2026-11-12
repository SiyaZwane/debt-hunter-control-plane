package com.debthunter.controlplane.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.testkit.RunBundleFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * CP-AC-85: a workload identity can write only for the repositories it has been granted. With two
 * repositories onboarded and a pipeline granted one of them, a run for the other is refused and
 * never reaches the queue; withdrawing the grant stops the next run for the first as well, because
 * the scope is read from the registry on every write, not cached from the last one.
 */
@Tag("integration")
class CpAc85_CrossRepoWriteRejectedTest {

  private static final String REPOS = "/api/v1/admin/repositories";
  private static final String GRANTS = "/api/v1/admin/grants";

  @Test
  void aPipelineWritesForItsGrantedRepositoryButNotForAnotherOnboardedOne() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.entry("acme/granted"))
          .expectStatus()
          .isCreated();
      h.admin(HttpMethod.POST, REPOS, AdminHarness.entry("acme/other")).expectStatus().isCreated();
      h.admin(
              HttpMethod.PUT,
              GRANTS + "?workloadIdentity=" + AdminHarness.PIPELINE + "&repository=acme/granted",
              null)
          .expectStatus()
          .isNoContent();

      h.publish(h.pipelineToken(), RunBundleFixtures.validV1("run-1", "acme/granted"))
          .expectStatus()
          .isAccepted();
      h.publish(h.pipelineToken(), RunBundleFixtures.validV1("run-2", "acme/other"))
          .expectStatus()
          .isForbidden()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("workload_scope_violation");

      assertThat(h.published).hasSize(1);
    }
  }

  @Test
  void withdrawingTheGrantStopsTheNextWriteForThatRepository() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.entry("acme/granted"))
          .expectStatus()
          .isCreated();
      String grant =
          GRANTS + "?workloadIdentity=" + AdminHarness.PIPELINE + "&repository=acme/granted";
      h.admin(HttpMethod.PUT, grant, null).expectStatus().isNoContent();
      h.publish(h.pipelineToken(), RunBundleFixtures.validV1("run-1", "acme/granted"))
          .expectStatus()
          .isAccepted();

      h.admin(HttpMethod.DELETE, grant, null).expectStatus().isNoContent();

      h.publish(h.pipelineToken(), RunBundleFixtures.validV1("run-2", "acme/granted"))
          .expectStatus()
          .isForbidden();
      assertThat(h.published).hasSize(1);
    }
  }

  @Test
  void anIdentityNeverGrantedAnythingWritesNowhere() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.entry("acme/granted"))
          .expectStatus()
          .isCreated();

      h.publish(h.pipelineToken(), RunBundleFixtures.validV1("run-1", "acme/granted"))
          .expectStatus()
          .isForbidden();

      assertThat(h.published).isEmpty();
    }
  }
}
