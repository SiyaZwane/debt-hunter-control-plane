package com.debthunter.controlplane.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.testkit.RunBundleFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The purpose of the administration API, end to end over HTTP: nothing can be published until an
 * administrator onboards the repository and grants the pipeline, and the grant can be withdrawn.
 */
@Tag("integration")
class AdminOnboardingEnablesPublishingTest {

  private static final String REPOS = "/api/v1/admin/repositories";
  private static final String GRANT =
      "/api/v1/admin/grants?workloadIdentity=" + AdminHarness.PIPELINE + "&repository=acme/widgets";

  @Test
  void aPipelineCanPublishExactlyWhileItIsOnboardedAndGranted() {
    try (AdminHarness h = new AdminHarness()) {
      String bundle = RunBundleFixtures.validV1();

      h.publish(h.pipelineToken(), bundle).expectStatus().isForbidden(); // nobody onboarded it

      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().isCreated();
      h.publish(h.pipelineToken(), bundle).expectStatus().isForbidden(); // onboarded, not granted

      h.admin(HttpMethod.PUT, GRANT, null).expectStatus().isNoContent();
      h.publish(h.pipelineToken(), bundle).expectStatus().isAccepted();
      assertThat(h.published).hasSize(1);
      assertThat(h.published.get(0).repository()).isEqualTo("acme/widgets");
      assertThat(h.published.get(0).workloadIdentity()).isEqualTo(AdminHarness.PIPELINE);

      h.admin(HttpMethod.DELETE, GRANT, null).expectStatus().isNoContent();
      h.publish(h.pipelineToken(), bundle).expectStatus().isForbidden(); // grant withdrawn
      assertThat(h.published).hasSize(1);
    }
  }

  @Test
  void offboardingStopsPublishingEvenForAGrantedPipeline() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().isCreated();
      h.admin(HttpMethod.PUT, GRANT, null).expectStatus().isNoContent();
      h.publish(h.pipelineToken(), RunBundleFixtures.validV1()).expectStatus().isAccepted();

      h.admin(HttpMethod.DELETE, REPOS + "/acme/widgets", null).expectStatus().isNoContent();

      h.publish(h.pipelineToken(), RunBundleFixtures.validV1()).expectStatus().isForbidden();
    }
  }

  @Test
  void aPipelineCannotGrantItselfAccess() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().isCreated();

      h.call(HttpMethod.PUT, GRANT, h.pipelineToken(), null).expectStatus().isForbidden();

      h.publish(h.pipelineToken(), RunBundleFixtures.validV1()).expectStatus().isForbidden();
    }
  }
}
