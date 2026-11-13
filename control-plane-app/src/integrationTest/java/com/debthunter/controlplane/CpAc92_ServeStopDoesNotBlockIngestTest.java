package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * CP-AC-92: stopping the read APIs does not affect ingest. The whole application: with the serve
 * zone stopped every read endpoint answers 503, and a run published meanwhile is still accepted and
 * ingested, to the same stored state as with the zone running.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CpAc92_ServeStopDoesNotBlockIngestTest {

  private static final TestJwtIssuer ISSUER = new TestJwtIssuer();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    ZoneTestSupport.baseline(registry, ISSUER);
  }

  @AfterAll
  static void stopIssuer() {
    ISSUER.close();
  }

  @Autowired private WebTestClient client;
  @Autowired private RepositoryRegistryService registry;
  @Autowired private FindingStateRepository states;
  @Autowired private ServeZone serve;

  @AfterEach
  void running() {
    serve.start();
  }

  private int portfolio() {
    return client
        .get()
        .uri("/api/v1/portfolio")
        .header("Authorization", "Bearer " + ISSUER.tokenWithRoles("leader", "ControlPlane.Leader"))
        .exchange()
        .returnResult(String.class)
        .getStatus()
        .value();
  }

  private void publish(String repository, String runId, String fingerprint) {
    client
        .post()
        .uri("/api/v1/runs")
        .header("Authorization", "Bearer " + ISSUER.token(ZoneTestSupport.PIPELINE))
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
  void cpAc92_aStoppedServeZoneAnswersUnavailableAndIngestCarriesOn() {
    ZoneTestSupport.onboard(registry, "acme/serve-stopped", "jira", "WID");
    assertThat(portfolio()).as("serving").isEqualTo(200);

    serve.stop();

    assertThat(portfolio()).as("stopped").isEqualTo(503);
    client
        .get()
        .uri("/api/v1/squads/team-a/findings")
        .header(
            "Authorization",
            "Bearer "
                + ISSUER.tokenWithRolesAndSquads(
                    "s", java.util.List.of("ControlPlane.Squad"), java.util.List.of("team-a")))
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("unavailable");
    publish("acme/serve-stopped", "s1", "fp-a");
    publish("acme/serve-stopped", "s2", "fp-b");

    serve.start();

    assertThat(portfolio()).as("serving again").isEqualTo(200);
  }
}
