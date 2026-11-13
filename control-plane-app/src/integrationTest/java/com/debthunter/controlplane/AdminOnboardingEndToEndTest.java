package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.store.audit.AuditEntry;
import com.debthunter.controlplane.store.audit.AuditTrail;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The whole application with nothing seeded behind its back: an administrator onboards a repository
 * and grants a pipeline over HTTP, the pipeline then publishes over HTTP, and the finding appears
 * in the real database after passing through real Kafka. No test code touches the registry.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminOnboardingEndToEndTest {

  private static final String PIPELINE = "svc-pipeline";
  private static final TestJwtIssuer ISSUER = new TestJwtIssuer();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("debthunter.controlplane.intake.encryption-key", () -> AppProperties.INTAKE_KEY);
    PostgresTestDatabase.Connection db = PostgresTestDatabase.freshSchemaConnection();
    registry.add(
        "debthunter.controlplane.security.jwk-set-uri", () -> ISSUER.startJwksServer().toString());
    registry.add("debthunter.controlplane.security.issuer", () -> TestJwtIssuer.ISSUER);
    registry.add("debthunter.controlplane.security.audience", () -> TestJwtIssuer.AUDIENCE);
    registry.add("debthunter.controlplane.database.url", db::jdbcUrl);
    registry.add("debthunter.controlplane.database.username", db::username);
    registry.add("debthunter.controlplane.database.password", db::password);
    registry.add("debthunter.controlplane.pseudonym.key", () -> AppProperties.PSEUDONYM_KEY);
    registry.add(
        "debthunter.controlplane.pseudonym.directory-key", () -> AppProperties.DIRECTORY_KEY);
  }

  @Autowired private WebTestClient client;
  @Autowired private FindingStateRepository states;
  @Autowired private AuditTrail auditTrail;

  @AfterAll
  static void stopIssuer() {
    ISSUER.close();
  }

  private WebTestClient.ResponseSpec call(
      HttpMethod method, String path, String token, String body) {
    WebTestClient.RequestBodySpec request =
        client.method(method).uri(path).header("Authorization", "Bearer " + token);
    request.header("Content-Type", "application/json");
    return (body == null ? request : request.bodyValue(body)).exchange();
  }

  private String admin() {
    return ISSUER.tokenWithRoles("admin-alice", "ControlPlane.Admin");
  }

  @Test
  void anAdministratorEnablesAPipelineOverHttpAndItsFindingsReachTheDatabase() {
    String repository = "acme/onboarded-by-api";
    String grant =
        "/api/v1/admin/grants?workloadIdentity=" + PIPELINE + "&repository=" + repository;
    String bundle =
        RunBundleBuilder.run()
            .id("e2e-1")
            .repository(repository)
            .commit("c1")
            .observing("fp-a")
            .build();

    // Before anyone has done anything, the pipeline is turned away.
    call(HttpMethod.POST, "/api/v1/runs", ISSUER.token(PIPELINE), bundle)
        .expectStatus()
        .isForbidden();

    call(
            HttpMethod.POST,
            "/api/v1/admin/repositories",
            admin(),
            """
            {"repository":"%s","owner":"team-a","tier":"tier-1","policyBundleVersion":"1.0",\
            "rolloutStage":"enforce","route":{"tracker":"jira","target":"WID"}}"""
                .formatted(repository))
        .expectStatus()
        .isCreated();
    call(HttpMethod.PUT, grant, admin(), null).expectStatus().isNoContent();

    call(HttpMethod.POST, "/api/v1/runs", ISSUER.token(PIPELINE), bundle)
        .expectStatus()
        .isAccepted();
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> states.find(repository, "default", "fp-a").isPresent());
    assertThat(states.find(repository, "default", "fp-a").get().state())
        .isEqualTo(FindingState.NEW);

    // Withdrawing the grant stops it again.
    call(HttpMethod.DELETE, grant, admin(), null).expectStatus().isNoContent();
    call(HttpMethod.POST, "/api/v1/runs", ISSUER.token(PIPELINE), bundle)
        .expectStatus()
        .isForbidden();

    // Every change is in the durable trail, in the database, not only in the log.
    assertThat(auditTrail.read(AuditEntry.Kind.ADMIN, Instant.EPOCH, 100))
        .filteredOn(e -> e.target().contains(repository))
        .extracting(AuditEntry::caller, AuditEntry::action)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("admin-alice", "ONBOARD_REPOSITORY"),
            org.assertj.core.groups.Tuple.tuple("admin-alice", "GRANT_WORKLOAD"),
            org.assertj.core.groups.Tuple.tuple("admin-alice", "REVOKE_WORKLOAD"));
  }

  @Test
  void aPipelinesTokenCannotAdministerTheRealApplicationEither() {
    call(HttpMethod.GET, "/api/v1/admin/repositories", ISSUER.token(PIPELINE), null)
        .expectStatus()
        .isForbidden();
    call(
            HttpMethod.PUT,
            "/api/v1/admin/grants?workloadIdentity=" + PIPELINE + "&repository=acme/anything",
            ISSUER.token(PIPELINE),
            null)
        .expectStatus()
        .isForbidden();
  }

  @Test
  void theAdministrationRoutesDemandAToken() {
    client.get().uri("/api/v1/admin/repositories").exchange().expectStatus().isUnauthorized();
  }
}
