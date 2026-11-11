package com.debthunter.controlplane.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Who may administer. A pipeline's token is valid and authenticated, and it still must not be able
 * to change who may publish: that would let any pipeline grant itself access to any repository.
 */
@Tag("integration")
class AdminAccessControlTest {

  private record Route(HttpMethod method, String path, String body) {}

  private static final List<Route> EVERY_ROUTE =
      List.of(
          new Route(HttpMethod.POST, "/api/v1/admin/repositories", AdminHarness.widgets()),
          new Route(HttpMethod.GET, "/api/v1/admin/repositories", null),
          new Route(HttpMethod.GET, "/api/v1/admin/repositories/acme/widgets", null),
          new Route(
              HttpMethod.PUT, "/api/v1/admin/repositories/acme/widgets", AdminHarness.widgets()),
          new Route(HttpMethod.DELETE, "/api/v1/admin/repositories/acme/widgets", null),
          new Route(
              HttpMethod.PUT,
              "/api/v1/admin/grants?workloadIdentity=svc&repository=acme/widgets",
              null),
          new Route(
              HttpMethod.DELETE,
              "/api/v1/admin/grants?workloadIdentity=svc&repository=acme/widgets",
              null),
          new Route(HttpMethod.GET, "/api/v1/admin/grants?workloadIdentity=svc", null));

  @Test
  void anUnauthenticatedCallerIsRejectedOnEveryRoute() {
    try (AdminHarness harness = new AdminHarness()) {
      for (Route r : EVERY_ROUTE) {
        harness.call(r.method(), r.path(), null, r.body()).expectStatus().isUnauthorized();
      }
    }
  }

  @Test
  void aPipelinesValidTokenIsForbiddenOnEveryRoute() {
    try (AdminHarness harness = new AdminHarness()) {
      for (Route r : EVERY_ROUTE) {
        harness
            .call(r.method(), r.path(), harness.pipelineToken(), r.body())
            .expectStatus()
            .isForbidden();
      }
      assertThat(harness.registry.all()).as("nothing was changed").isEmpty();
      assertThat(harness.auditTrail).isEmpty();
    }
  }

  @Test
  void aTokenWithSomeOtherRoleIsForbidden() {
    try (AdminHarness harness = new AdminHarness()) {
      String reader = harness.issuer.tokenWithRoles("somebody", "ControlPlane.Reader");

      for (Route r : EVERY_ROUTE) {
        harness.call(r.method(), r.path(), reader, r.body()).expectStatus().isForbidden();
      }
    }
  }

  @Test
  void aTokenWithTheAdministratorRoleAmongSeveralIsAllowed() {
    try (AdminHarness harness = new AdminHarness()) {
      String token = harness.issuer.tokenWithRoles("alice", "Other", "ControlPlane.Admin");

      harness.call(HttpMethod.GET, "/api/v1/admin/repositories", token, null).expectStatus().isOk();
    }
  }

  @Test
  void anAdministratorIsNotAPipelineSoItCannotPublishUntilItIsGrantedARepositoryLikeAnyoneElse() {
    try (AdminHarness harness = new AdminHarness()) {
      harness.registry.onboard(
          new com.debthunter.controlplane.store.registry.OnboardedRepository(
              "acme/widgets",
              "t",
              "t",
              "1",
              "s",
              new com.debthunter.controlplane.store.registry.WorkItemRoute("a", "b")));

      harness
          .publish(
              harness.adminToken(), com.debthunter.controlplane.testkit.RunBundleFixtures.validV1())
          .expectStatus()
          .isForbidden();
    }
  }

  @Test
  void theNonAdminRoutesAreUnaffected() {
    try (AdminHarness harness = new AdminHarness()) {
      harness.registry.onboard(
          new com.debthunter.controlplane.store.registry.OnboardedRepository(
              "acme/widgets",
              "t",
              "t",
              "1",
              "s",
              new com.debthunter.controlplane.store.registry.WorkItemRoute("a", "b")));
      harness.registry.grantWorkload(AdminHarness.PIPELINE, "acme/widgets");

      harness
          .publish(
              harness.pipelineToken(),
              com.debthunter.controlplane.testkit.RunBundleFixtures.validV1())
          .expectStatus()
          .isAccepted();
    }
  }
}
