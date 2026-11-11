package com.debthunter.controlplane.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.egress.alert.SecurityAlertType;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-02: a pipeline may only publish for repositories within its workload identity's scope. A
 * scope violation is refused with 403 and raises a security alert; a token that is not genuine for
 * this service is refused with 401 before scope is even considered.
 */
@Tag("integration")
class CpAc02_WrongScopeReturns403Test {

  @Test
  void cpAc02_publishingForAnotherRepositoryIsForbiddenAndAlerted() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      harness
          .publish(
              harness.tokenFor(IngestionHarness.SERVICE),
              RunBundleFixtures.validV1("run-1", "acme/someone-elses-repo"))
          .expectStatus()
          .isForbidden()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("workload_scope_violation");

      assertThat(harness.queue.published()).isEmpty();
      assertThat(harness.alerts.alerts()).hasSize(1);
      assertThat(harness.alerts.alerts().get(0).type())
          .isEqualTo(SecurityAlertType.WORKLOAD_SCOPE_VIOLATION);
      assertThat(harness.alerts.alerts().get(0).workloadIdentity())
          .isEqualTo(IngestionHarness.SERVICE);
      assertThat(harness.alerts.alerts().get(0).declaredRepository())
          .isEqualTo("acme/someone-elses-repo");
    }
  }

  @Test
  void cpAc02_anIdentityWithNoScopeAtAllIsForbidden() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      harness
          .publish(harness.tokenFor("svc-unregistered"), RunBundleFixtures.validV1())
          .expectStatus()
          .isForbidden();
    }
  }

  @Test
  void cpAc02_aRequestWithNoTokenIsUnauthorised() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      harness.publish(null, RunBundleFixtures.validV1()).expectStatus().isUnauthorized();

      assertThat(harness.queue.published()).isEmpty();
      assertThat(harness.alerts.alerts()).isEmpty();
    }
  }

  @Test
  void cpAc02_aTokenThatIsNotAValidJwtIsUnauthorised() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      harness.publish("not-a-jwt", RunBundleFixtures.validV1()).expectStatus().isUnauthorized();
    }
  }

  @Test
  void cpAc02_aTokenMintedForAnotherAudienceIsUnauthorisedEvenThoughItsSignatureIsGenuine() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      // A tenant signs tokens for every app in it with the same keys; the audience check is what
      // stops a token meant for some other app being accepted here.
      String token =
          harness.issuer.token(
              IngestionHarness.SERVICE,
              TestJwtIssuer.ISSUER,
              "api://some-other-application",
              Instant.now().plus(Duration.ofHours(1)));

      harness.publish(token, RunBundleFixtures.validV1()).expectStatus().isUnauthorized();
    }
  }

  @Test
  void cpAc02_aTokenFromAnotherIssuerIsUnauthorised() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      String token =
          harness.issuer.token(
              IngestionHarness.SERVICE,
              "https://login.test/some-other-tenant/v2.0",
              TestJwtIssuer.AUDIENCE,
              Instant.now().plus(Duration.ofHours(1)));

      harness.publish(token, RunBundleFixtures.validV1()).expectStatus().isUnauthorized();
    }
  }

  @Test
  void cpAc02_anExpiredTokenIsUnauthorised() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      String token =
          harness.issuer.token(
              IngestionHarness.SERVICE,
              TestJwtIssuer.ISSUER,
              TestJwtIssuer.AUDIENCE,
              Instant.now().minus(Duration.ofHours(1)));

      harness.publish(token, RunBundleFixtures.validV1()).expectStatus().isUnauthorized();
    }
  }
}
