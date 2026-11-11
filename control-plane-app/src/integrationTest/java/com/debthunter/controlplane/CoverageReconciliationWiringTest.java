package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.analytics.coverage.CoverageReport;
import com.debthunter.controlplane.analytics.coverage.CoverageState;
import com.debthunter.controlplane.analytics.coverage.CoverageTracker;
import com.debthunter.controlplane.analytics.coverage.RepositoryCoverage;
import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.testkit.FakeEstateServer;
import com.debthunter.controlplane.testkit.FakeEstateServer.Response;
import com.debthunter.controlplane.testkit.KafkaTestBroker;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Coverage tracking in the whole application: the estate comes from a (fake) GitHub over real HTTP,
 * the registry and run store are the real PostgreSQL ones, and runs arrive through the real HTTP
 * edge and Kafka.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoverageReconciliationWiringTest {

  private static final String PIPELINE = "svc-pipeline";
  private static final String TOKEN = "gh-test-token-value";
  private static final TestJwtIssuer ISSUER = new TestJwtIssuer();
  private static final FakeEstateServer GITHUB = new FakeEstateServer();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
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
        "debthunter.controlplane.intake.bootstrap-servers", KafkaTestBroker::bootstrapServers);
    registry.add("debthunter.controlplane.intake.topic", () -> "cov-" + UUID.randomUUID());
    registry.add("debthunter.controlplane.intake.consumer-group", () -> "cov-" + UUID.randomUUID());
    registry.add("debthunter.controlplane.analytics.coverage.estate.provider", () -> "github");
    registry.add("debthunter.controlplane.analytics.coverage.estate.organization", () -> "acme");
    registry.add("debthunter.controlplane.analytics.coverage.estate.token", () -> TOKEN);
    registry.add(
        "debthunter.controlplane.analytics.coverage.estate.api-url",
        () -> GITHUB.baseUrl().toString());
    registry.add("debthunter.controlplane.analytics.coverage.cadence", () -> "P7D");
    registry.add("debthunter.controlplane.analytics.coverage.reconcile-interval", () -> "PT24H");
  }

  @Autowired private WebTestClient client;
  @Autowired private RepositoryRegistryService registry;
  @Autowired private CoverageTracker tracker;
  @Autowired private FindingStateRepository states;
  @Autowired private CoverageReconciliationLifecycle reconciliation;

  @AfterAll
  static void stop() {
    ISSUER.close();
    GITHUB.close();
  }

  private static void estateLists(String... fullNames) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < fullNames.length; i++) {
      json.append(i == 0 ? "" : ",")
          .append("{\"full_name\":\"")
          .append(fullNames[i])
          .append("\",\"archived\":")
          .append(fullNames[i].contains("archived"))
          .append("}");
    }
    GITHUB.respondWith(r -> Response.json(json.append("]").toString()));
  }

  private RepositoryCoverage of(CoverageReport report, String repository) {
    return report.repositories().stream()
        .filter(r -> r.repository().equals(repository))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void theEstateComesFromGitHubAndTheReportShowsWhoIsWatchedSilentOrForgotten() {
    estateLists("acme/watched", "acme/forgotten", "acme/archived-old");
    registry.onboard(
        new OnboardedRepository(
            "acme/watched", "team", "tier-1", "1.0", "enforce", new WorkItemRoute("jira", "W")));
    registry.grantWorkload(PIPELINE, "acme/watched");

    reconciliation.runOnce();

    CoverageReport report = tracker.latest().value().orElseThrow();
    assertThat(report.estateAvailable()).isTrue();
    assertThat(of(report, "acme/forgotten").state()).isEqualTo(CoverageState.NEVER_ONBOARDED);
    assertThat(of(report, "acme/watched").state()).isEqualTo(CoverageState.SILENT);
    assertThat(report.repositories())
        .extracting(RepositoryCoverage::repository)
        .doesNotContain("acme/archived-old");
    assertThat(GITHUB.requests())
        .anySatisfy(r -> assertThat(r.headers()).containsEntry("authorization", "Bearer " + TOKEN));

    // The pipeline starts reporting, over the real edge, and the next reconciliation sees it.
    client
        .post()
        .uri("/api/v1/runs")
        .header("Authorization", "Bearer " + ISSUER.token(PIPELINE))
        .header("Content-Type", "application/json")
        .bodyValue(
            RunBundleBuilder.run()
                .id("cov-1")
                .repository("acme/watched")
                .commit("c1")
                .timestamp(Instant.now().toString())
                .observing("fp-a")
                .build())
        .exchange()
        .expectStatus()
        .isAccepted();
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> states.find("acme/watched", "default", "fp-a").isPresent());

    reconciliation.runOnce();

    assertThat(of(tracker.latest().value().orElseThrow(), "acme/watched").state())
        .isEqualTo(CoverageState.REPORTING);
  }

  @Test
  void anEstateThatIsDownIsReportedAsSuchAndTheApplicationCarriesOn() {
    GITHUB.respondWith(r -> Response.status(500));

    reconciliation.runOnce();

    CoverageReport report = tracker.latest().value().orElseThrow();
    assertThat(report.estateAvailable()).isFalse();
    assertThat(report.warnings()).anyMatch(w -> w.contains("could not be listed"));
    assertThat(report.warnings()).noneMatch(w -> w.contains(TOKEN));
    assertThat(report.inState(CoverageState.NEVER_ONBOARDED)).isEmpty();
  }

  @Test
  void theScheduledReconciliationRunsOnItsOwnAndStoresAReport() {
    estateLists("acme/anything");

    await().atMost(Duration.ofSeconds(120)).until(() -> tracker.latest().value().isPresent());

    assertThat(reconciliation.isRunning()).isTrue();
  }
}
