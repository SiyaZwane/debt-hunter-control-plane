package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.ingest.intake.RunIntakeQueuePublisher;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The contract between the two repositories: the real data-plane CLI, run as a separate process,
 * publishes a real report to the running control plane with a real signed token, and the control
 * plane accepts it and enqueues exactly what was sent.
 *
 * <p>If the data plane's report shape or its publish behaviour drifts away from what the control
 * plane expects, this is the test that fails.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataPlanePublishContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String PIPELINE = "svc-pipeline";
  private static final TestJwtIssuer ISSUER = new TestJwtIssuer();
  private static final List<IntakeEnvelope> ENQUEUED = new CopyOnWriteArrayList<>();
  private static final Path WORK = createWorkDirectory();
  private static final Path REPOSITORY = createRepository();

  @LocalServerPort private int port;

  @Autowired private RepositoryRegistryService registry;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add(
        "debthunter.controlplane.security.jwk-set-uri", () -> ISSUER.startJwksServer().toString());
    registry.add("debthunter.controlplane.security.issuer", () -> TestJwtIssuer.ISSUER);
    registry.add("debthunter.controlplane.security.audience", () -> TestJwtIssuer.AUDIENCE);
    registry.add("debthunter.controlplane.intake.encryption-key", () -> AppProperties.INTAKE_KEY);
    registry.add("debthunter.controlplane.intake.listener-enabled", () -> "false");
    PostgresTestDatabase.Connection db = PostgresTestDatabase.freshSchemaConnection();
    registry.add("debthunter.controlplane.database.url", db::jdbcUrl);
    registry.add("debthunter.controlplane.database.username", db::username);
    registry.add("debthunter.controlplane.database.password", db::password);
    registry.add("debthunter.controlplane.pseudonym.key", () -> AppProperties.PSEUDONYM_KEY);
    registry.add(
        "debthunter.controlplane.pseudonym.directory-key", () -> AppProperties.DIRECTORY_KEY);
  }

  @BeforeEach
  void onboardTheRepositoryAndGrantThePipeline() {
    // Today's data plane declares the repository's local path as the repository identity.
    String repository = REPOSITORY.toString();
    if (!registry.isOnboarded(repository)) {
      registry.onboard(
          new OnboardedRepository(
              repository, "team-a", "tier-1", "1.0", "enforce", new WorkItemRoute("jira", "WID")));
      registry.grantWorkload(PIPELINE, repository);
    }
  }

  @TestConfiguration
  static class CapturingQueueConfiguration {
    @Bean
    @Primary
    RunIntakeQueuePublisher capturingQueue() {
      return new RunIntakeQueuePublisher() {
        @Override
        public CompletionStage<Void> publish(IntakeEnvelope envelope) {
          ENQUEUED.add(envelope);
          return CompletableFuture.completedFuture(null);
        }
      };
    }
  }

  @AfterAll
  static void stopIssuer() {
    ISSUER.close();
  }

  @Test
  void theRealDataPlanePublishesItsReportAndTheControlPlaneEnqueuesItIntact() throws Exception {
    Path output = WORK.resolve("out");

    DataPlaneRunner.Result scan =
        DataPlaneRunner.scan(
            REPOSITORY,
            output,
            "http://127.0.0.1:" + port + "/api/v1/runs",
            ISSUER.token(PIPELINE));

    assertThat(scan.exitCode()).as(scan.output()).isIn(0, 1);
    assertThat(Files.readString(output.resolve("summary.md")))
        .as("publication succeeded, so there is no warning")
        .doesNotContain("failed to publish");

    assertThat(ENQUEUED).hasSize(1);
    IntakeEnvelope envelope = ENQUEUED.get(0);
    JsonNode written = MAPPER.readTree(output.resolve("debt-hunter.json").toFile());
    assertThat(envelope.workloadIdentity()).isEqualTo(PIPELINE);
    assertThat(envelope.repository()).isEqualTo(REPOSITORY.toString());
    assertThat(envelope.schemaVersion()).isEqualTo(written.path("schemaVersion").asText());
    assertThat(envelope.runId()).isEqualTo(written.path("run").path("id").asText());
    assertThat(MAPPER.readTree(envelope.payloadJson()))
        .as("what was enqueued is exactly the report the data plane wrote")
        .isEqualTo(written);
  }

  private static Path createWorkDirectory() {
    try {
      return Files.createTempDirectory("control-plane-contract-");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Path createRepository() {
    try {
      return DataPlaneRunner.createRepository(WORK.resolve("repo"));
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }
}
