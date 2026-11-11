package com.debthunter.controlplane.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

@Tag("integration")
class AdminGrantsApiTest {

  private static final String REPOS = "/api/v1/admin/repositories";
  private static final String GRANTS = "/api/v1/admin/grants";

  private static String grant(String workload, String repository) {
    return GRANTS + "?workloadIdentity=" + workload + "&repository=" + repository;
  }

  @Test
  void aWorkloadCanBeGrantedAnOnboardedRepositoryAndSeesItListed() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().isCreated();

      h.admin(HttpMethod.PUT, grant("svc-a", "acme/widgets"), null).expectStatus().isNoContent();

      h.admin(HttpMethod.GET, GRANTS + "?workloadIdentity=svc-a", null)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.workloadIdentity")
          .isEqualTo("svc-a")
          .jsonPath("$.repositories[0]")
          .isEqualTo("acme/widgets");
      assertThat(h.registry.mayWrite("svc-a", "acme/widgets")).isTrue();
    }
  }

  @Test
  void grantingIsIdempotent() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().isCreated();

      h.admin(HttpMethod.PUT, grant("svc-a", "acme/widgets"), null).expectStatus().isNoContent();
      h.admin(HttpMethod.PUT, grant("svc-a", "acme/widgets"), null).expectStatus().isNoContent();

      assertThat(h.registry.repositoriesOf("svc-a")).hasSize(1);
    }
  }

  @Test
  void aGrantForARepositoryThatIsNotOnboardedIsNotFoundAndGrantsNothing() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.PUT, grant("svc-a", "acme/ghost"), null)
          .expectStatus()
          .isNotFound()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("not_onboarded");

      assertThat(h.registry.repositoriesOf("svc-a")).isEmpty();
    }
  }

  @Test
  void revokingWithdrawsThePermissionAndIsIdempotent() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().isCreated();
      h.admin(HttpMethod.PUT, grant("svc-a", "acme/widgets"), null).expectStatus().isNoContent();

      h.admin(HttpMethod.DELETE, grant("svc-a", "acme/widgets"), null).expectStatus().isNoContent();
      h.admin(HttpMethod.DELETE, grant("svc-a", "acme/widgets"), null).expectStatus().isNoContent();

      assertThat(h.registry.mayWrite("svc-a", "acme/widgets")).isFalse();
    }
  }

  @Test
  void aWorkloadWithNoGrantsHasAnEmptyList() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.GET, GRANTS + "?workloadIdentity=nobody", null)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.repositories.length()")
          .isEqualTo(0);
    }
  }

  @Test
  void offboardingARepositoryWithdrawsEveryGrantForIt() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().isCreated();
      h.admin(HttpMethod.PUT, grant("svc-a", "acme/widgets"), null).expectStatus().isNoContent();
      h.admin(HttpMethod.PUT, grant("svc-b", "acme/widgets"), null).expectStatus().isNoContent();

      h.admin(HttpMethod.DELETE, REPOS + "/acme/widgets", null).expectStatus().isNoContent();

      h.admin(HttpMethod.GET, GRANTS + "?workloadIdentity=svc-a", null)
          .expectBody()
          .jsonPath("$.repositories.length()")
          .isEqualTo(0);
      assertThat(h.registry.mayWrite("svc-b", "acme/widgets")).isFalse();
    }
  }

  @Test
  void aMissingOrBlankParameterIsABadRequest() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.PUT, GRANTS + "?workloadIdentity=svc-a", null)
          .expectStatus()
          .isBadRequest();
      h.admin(HttpMethod.PUT, GRANTS + "?workloadIdentity=&repository=acme/widgets", null)
          .expectStatus()
          .isBadRequest();
    }
  }
}
