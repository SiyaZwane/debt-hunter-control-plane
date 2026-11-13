package com.debthunter.controlplane;

import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageEvent;
import com.debthunter.controlplane.store.rollout.StageRecord;
import com.debthunter.controlplane.store.rollout.StageRepository;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.time.Instant;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Shared set-up for the tests that run the whole application with its zones. */
final class ZoneTestSupport {

  static final String PIPELINE = "svc-pipeline";

  private ZoneTestSupport() {}

  /** The configuration every whole-application test needs, on a fresh database. */
  static void baseline(DynamicPropertyRegistry registry, TestJwtIssuer issuer) {
    PostgresTestDatabase.Connection db = PostgresTestDatabase.freshSchemaConnection();
    registry.add("debthunter.controlplane.intake.encryption-key", () -> AppProperties.INTAKE_KEY);
    registry.add(
        "debthunter.controlplane.security.jwk-set-uri", () -> issuer.startJwksServer().toString());
    registry.add("debthunter.controlplane.security.issuer", () -> TestJwtIssuer.ISSUER);
    registry.add("debthunter.controlplane.security.audience", () -> TestJwtIssuer.AUDIENCE);
    registry.add("debthunter.controlplane.database.url", db::jdbcUrl);
    registry.add("debthunter.controlplane.database.username", db::username);
    registry.add("debthunter.controlplane.database.password", db::password);
    registry.add("debthunter.controlplane.pseudonym.key", () -> AppProperties.PSEUDONYM_KEY);
    registry.add(
        "debthunter.controlplane.pseudonym.directory-key", () -> AppProperties.DIRECTORY_KEY);
  }

  /**
   * Onboards a repository owned by {@code team-a}, routed to a tracker, and lets the pipeline
   * publish.
   */
  static void onboard(
      RepositoryRegistryService registry, String repository, String tracker, String project) {
    registry.onboard(
        new OnboardedRepository(
            repository, "team-a", "tier-1", "1.0", "enforce", new WorkItemRoute(tracker, project)));
    registry.grantWorkload(PIPELINE, repository);
  }

  /** Puts a repository in the rollout at a stage. */
  static void enrol(StageRepository stages, String repository, RolloutStage stage) {
    Instant now = Instant.now();
    stages.apply(
        new StageRecord(repository, stage, now, "team-a", Map.of(), null, null),
        new StageEvent(
            repository, StageEvent.Kind.ENROLLED, null, stage, now, "test", Map.of(), null));
  }
}
