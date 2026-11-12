package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.analytics.readmodel.HotspotPoint;
import com.debthunter.controlplane.analytics.readmodel.InMemoryReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.OpenFinding;
import com.debthunter.controlplane.analytics.readmodel.ProjectionBatch;
import com.debthunter.controlplane.analytics.readmodel.ReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.ResolutionRecord;
import com.debthunter.controlplane.analytics.readmodel.RuleDelta;
import com.debthunter.controlplane.analytics.readmodel.RunSnapshot;
import com.debthunter.controlplane.analytics.trend.TrendEngine;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * CP-AC-32: stopping the projector does not block ingest. The whole application, real Kafka and
 * real PostgreSQL, with the projector made to fail, to hang, and to be switched off — and ingest
 * carries on regardless, with only the read models going stale.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CpAc32_ProjectorStopDoesNotBlockIngestTest {

  private static final String PIPELINE = "svc-pipeline";
  private static final TestJwtIssuer ISSUER = new TestJwtIssuer();

  /** A read model whose behaviour the test controls. */
  static final class ControllableReadModel implements ReadModelRepository {
    enum Mode {
      NORMAL,
      FAIL,
      HANG
    }

    private final InMemoryReadModelRepository delegate = new InMemoryReadModelRepository();
    volatile Mode mode = Mode.NORMAL;
    final AtomicInteger attempts = new AtomicInteger();
    final CountDownLatch neverReleased = new CountDownLatch(1);

    private void behave() {
      attempts.incrementAndGet();
      if (mode == Mode.FAIL) {
        throw new IllegalStateException("read model store is down");
      }
      if (mode == Mode.HANG) {
        try {
          neverReleased.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public long checkpoint(String name) {
      behave();
      return delegate.checkpoint(name);
    }

    @Override
    public Optional<Instant> lastUpdated() {
      return delegate.lastUpdated();
    }

    @Override
    public Optional<OpenFinding> openFinding(String r, String s, String f) {
      return delegate.openFinding(r, s, f);
    }

    @Override
    public void commit(ProjectionBatch batch, Instant now) {
      behave();
      delegate.commit(batch, now);
    }

    @Override
    public List<RunSnapshot> latestSnapshots(java.util.Collection<String> repositories) {
      return delegate.latestSnapshots(repositories);
    }

    @Override
    public List<RunSnapshot> snapshots(String r, String s, Instant a, Instant b) {
      return delegate.snapshots(r, s, a, b);
    }

    @Override
    public List<RuleDelta> ruleDeltas(String r, String s, Instant a, Instant b) {
      return delegate.ruleDeltas(r, s, a, b);
    }

    @Override
    public List<ResolutionRecord> resolutions(String r, String s, Instant a, Instant b) {
      return delegate.resolutions(r, s, a, b);
    }

    @Override
    public List<OpenFinding> openFindings(String r, String s) {
      return delegate.openFindings(r, s);
    }

    @Override
    public List<HotspotPoint> hotspots(String r, String s, Instant a, Instant b) {
      return delegate.hotspots(r, s, a, b);
    }
  }

  private static final ControllableReadModel READ_MODEL = new ControllableReadModel();

  @TestConfiguration
  static class ControllableReadModelConfiguration {
    @Bean
    @Primary
    ReadModelRepository controllableReadModel() {
      return READ_MODEL;
    }
  }

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
    registry.add("debthunter.controlplane.analytics.projection-interval", () -> "PT0.2S");
    registry.add("debthunter.controlplane.analytics.stale-after", () -> "PT2S");
  }

  @Autowired private WebTestClient client;
  @Autowired private RepositoryRegistryService registry;
  @Autowired private FindingStateRepository states;
  @Autowired private ProjectorLifecycle projectorLifecycle;
  @Autowired private TrendEngine trends;

  @AfterAll
  static void stopIssuer() {
    ISSUER.close();
  }

  @BeforeEach
  void normalAgain() {
    READ_MODEL.mode = ControllableReadModel.Mode.NORMAL;
    if (!projectorLifecycle.isRunning()) {
      projectorLifecycle.start();
    }
  }

  private void onboard(String repository) {
    registry.onboard(
        new OnboardedRepository(
            repository, "team-a", "tier-1", "1.0", "enforce", new WorkItemRoute("jira", "WID")));
    registry.grantWorkload(PIPELINE, repository);
  }

  private void publishAndExpectItToBeIngested(String repository, String runId, String fingerprint) {
    client
        .post()
        .uri("/api/v1/runs")
        .header("Authorization", "Bearer " + ISSUER.token(PIPELINE))
        .header("Content-Type", "application/json")
        .bodyValue(
            RunBundleBuilder.run()
                .id(runId)
                .repository(repository)
                .commit(runId)
                .observing(fingerprint)
                .build())
        .exchange()
        .expectStatus()
        .isAccepted();
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> states.find(repository, "default", fingerprint).isPresent());
    assertThat(states.find(repository, "default", fingerprint).get().state())
        .isEqualTo(FindingState.NEW);
  }

  @Test
  void cpAc32_aProjectorThatKeepsFailingDoesNotAffectIngest() {
    onboard("acme/failing-projector");
    READ_MODEL.mode = ControllableReadModel.Mode.FAIL;
    int before = READ_MODEL.attempts.get();
    await().atMost(Duration.ofSeconds(30)).until(() -> READ_MODEL.attempts.get() > before + 2);

    publishAndExpectItToBeIngested("acme/failing-projector", "f1", "fp-a");
  }

  @Test
  void cpAc32_aProjectorThatHangsForeverDoesNotAffectIngest() {
    onboard("acme/hung-projector");
    READ_MODEL.mode = ControllableReadModel.Mode.HANG;
    int before = READ_MODEL.attempts.get();
    await().atMost(Duration.ofSeconds(30)).until(() -> READ_MODEL.attempts.get() > before);

    publishAndExpectItToBeIngested("acme/hung-projector", "h1", "fp-a");
    publishAndExpectItToBeIngested("acme/hung-projector", "h2", "fp-b");
  }

  @Test
  void cpAc32_aStoppedProjectorDoesNotAffectIngestAndTheReadModelsReportThemselvesStale() {
    onboard("acme/stopped-projector");
    await().atMost(Duration.ofSeconds(30)).until(() -> READ_MODEL.lastUpdated().isPresent());
    assertThat(
            trends
                .findingRates("acme/stopped-projector", "default", Instant.EPOCH, Instant.now())
                .stale())
        .as("fresh while the projector is running")
        .isFalse();

    projectorLifecycle.stop();

    publishAndExpectItToBeIngested("acme/stopped-projector", "s1", "fp-a");
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                trends
                    .findingRates("acme/stopped-projector", "default", Instant.EPOCH, Instant.now())
                    .stale());
  }
}
