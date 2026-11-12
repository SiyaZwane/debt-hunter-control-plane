package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.quarantine.QuarantineRepository;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.store.runs.RunRepository;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The whole assembled application, as deployed: a real HTTP request with a real signed token, a
 * real Kafka broker, the real queue listener and pipeline, and a real PostgreSQL behind it.
 *
 * <p>Every earlier step was tested in isolation. This is the test that says they work together.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullPipelineWiringTest {

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
  }

  @Autowired private WebTestClient client;
  @Autowired private RepositoryRegistryService registry;
  @Autowired private FindingStateRepository states;
  @Autowired private TransitionLogRepository transitions;
  @Autowired private QuarantineRepository quarantine;
  @Autowired private RunRepository runs;
  @Autowired private DataSource dataSource;

  @AfterAll
  static void stopIssuer() {
    ISSUER.close();
  }

  private void onboard(String repository) {
    registry.onboard(
        new OnboardedRepository(
            repository, "team-a", "tier-1", "1.0", "enforce", new WorkItemRoute("jira", "WID")));
    registry.grantWorkload(PIPELINE, repository);
  }

  private WebTestClient.ResponseSpec publish(String bundleJson) {
    return client
        .post()
        .uri("/api/v1/runs")
        .header("Authorization", "Bearer " + ISSUER.token(PIPELINE))
        .header("Content-Type", "application/json")
        .bodyValue(bundleJson)
        .exchange();
  }

  private FindingState awaitState(String repository, String fingerprint, FindingState expected) {
    await()
        .atMost(Duration.ofSeconds(60))
        .until(
            () ->
                states
                    .find(repository, "default", fingerprint)
                    .map(r -> r.state() == expected)
                    .orElse(false));
    return states.find(repository, "default", fingerprint).orElseThrow().state();
  }

  @Test
  void aPublishedBundleReachesTheDatabaseAndTheLifecycleAdvancesAcrossRuns() {
    String repository = "acme/lifecycle";
    onboard(repository);

    publish(
            RunBundleBuilder.run()
                .id("l1")
                .repository(repository)
                .commit("c1")
                .observing("fp-a")
                .build())
        .expectStatus()
        .isAccepted();
    assertThat(awaitState(repository, "fp-a", FindingState.NEW)).isEqualTo(FindingState.NEW);

    publish(
            RunBundleBuilder.run()
                .id("l2")
                .repository(repository)
                .commit("c2")
                .observing("fp-a")
                .build())
        .expectStatus()
        .isAccepted();
    awaitState(repository, "fp-a", FindingState.OPEN);

    publish(RunBundleBuilder.run().id("l3").repository(repository).commit("c3").build())
        .expectStatus()
        .isAccepted();
    awaitState(repository, "fp-a", FindingState.RESOLVED);

    assertThat(transitions.historyOf(repository, "default", "fp-a"))
        .extracting(FindingTransitionEvent::toState)
        .containsExactly(FindingState.NEW, FindingState.OPEN, FindingState.RESOLVED);
    assertThat(runs.find("l3")).isPresent();
  }

  @Test
  void aDegradedRunDoesNotResolveAnythingEvenThroughTheWholeApplication() {
    String repository = "acme/degraded";
    onboard(repository);

    publish(
            RunBundleBuilder.run()
                .id("d1")
                .repository(repository)
                .commit("c1")
                .observing("fp-a")
                .build())
        .expectStatus()
        .isAccepted();
    awaitState(repository, "fp-a", FindingState.NEW);
    publish(RunBundleBuilder.run().id("d2").repository(repository).commit("c2").degraded().build())
        .expectStatus()
        .isAccepted();
    await().atMost(Duration.ofSeconds(60)).until(() -> runs.find("d2").isPresent());

    assertThat(states.find(repository, "default", "fp-a").orElseThrow().state())
        .isEqualTo(FindingState.NEW);
  }

  @Test
  void aRepositoryNobodyOnboardedIsRefusedByTheRegistry() {
    publish(
            RunBundleBuilder.run()
                .id("x1")
                .repository("acme/never-onboarded")
                .observing("fp-a")
                .build())
        .expectStatus()
        .isForbidden();
  }

  @Test
  void aWorkloadThatWasNotGrantedTheRepositoryIsRefusedEvenThoughItIsOnboarded() {
    registry.onboard(
        new OnboardedRepository(
            "acme/not-granted",
            "team-a",
            "tier-1",
            "1.0",
            "enforce",
            new WorkItemRoute("jira", "WID")));

    publish(
            RunBundleBuilder.run()
                .id("x2")
                .repository("acme/not-granted")
                .observing("fp-a")
                .build())
        .expectStatus()
        .isForbidden();
  }

  @Test
  void aBundleThatCanNeverBeProcessedIsAcceptedThenSetAsideWithoutItsContent() {
    String repository = "acme/quarantine";
    onboard(repository);

    publish(
            RunBundleBuilder.run()
                .id("q1")
                .repository(repository)
                .timestamp("not a time")
                .observing("fp-a")
                .authoredBy("carol@example.com")
                .build())
        .expectStatus()
        .isAccepted();

    await().atMost(Duration.ofSeconds(60)).until(() -> !quarantine.forRun("q1").isEmpty());
    assertThat(quarantine.forRun("q1"))
        .singleElement()
        .satisfies(q -> assertThat(q.reason()).contains("run.timestamp"));
    assertThat(quarantine.forRun("q1").toString()).doesNotContainIgnoringCase("carol");
    assertThat(states.find(repository, "default", "fp-a")).isEmpty();
  }

  @Test
  void noRawContributorIdentityIsAnywhereInTheDatabaseAfterIngest() {
    String repository = "acme/identity";
    onboard(repository);

    publish(
            RunBundleBuilder.run()
                .id("i1")
                .repository(repository)
                .observing("fp-a")
                .authoredBy("alice@example.com", "Bob Builder")
                .build())
        .expectStatus()
        .isAccepted();
    awaitState(repository, "fp-a", FindingState.NEW);

    // Every row of every table, as text. Nothing about who the contributors are may appear.
    Jdbc jdbc = new Jdbc(dataSource);
    List<String> tables =
        jdbc.query(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()"
                + " AND table_type = 'BASE TABLE'",
            row -> row.getString("table_name"));
    List<String> rows = new ArrayList<>();
    for (String table : tables) {
      rows.addAll(
          jdbc.query(
              "SELECT row_to_json(t)::text AS r FROM " + table + " t", row -> row.getString("r")));
    }

    assertThat(tables).contains("finding", "run", "transition_log", "finding_state");
    assertThat(rows).isNotEmpty();
    assertThat(String.join("\n", rows))
        .doesNotContainIgnoringCase("alice")
        .doesNotContainIgnoringCase("example.com")
        .doesNotContainIgnoringCase("bob")
        .doesNotContainIgnoringCase("builder");
    assertThat(
            jdbc.query(
                "SELECT contributors FROM finding WHERE run_id = 'i1'",
                row -> row.getString("contributors")))
        .singleElement()
        .satisfies(
            c -> assertThat(c.split(",")).hasSize(2).allMatch(p -> p.matches("[0-9a-f]{64}")));
  }
}
