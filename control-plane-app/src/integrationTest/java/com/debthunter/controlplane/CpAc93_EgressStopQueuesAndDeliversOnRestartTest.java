package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageRepository;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * CP-AC-93: stopping egress queues delivery rather than losing or blocking it. The whole
 * application with a tracker: with egress stopped a run is still ingested and nothing reaches the
 * tracker; when egress starts again the work item is created, once.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CpAc93_EgressStopQueuesAndDeliversOnRestartTest {

  private static final TestJwtIssuer ISSUER = new TestJwtIssuer();
  private static final FakeTrackerServer TRACKER = new FakeTrackerServer();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    ZoneTestSupport.baseline(registry, ISSUER);
    String tracker = "debthunter.controlplane.zones.egress.trackers.azure-boards.";
    registry.add(tracker + "base-url", () -> TRACKER.uri().toString());
    registry.add(tracker + "authorization", () -> "Basic c2VjcmV0");
    registry.add("debthunter.controlplane.zones.egress.delivery.poll-interval", () -> "PT0.2S");
    registry.add("debthunter.controlplane.zones.egress.delivery.initial-backoff", () -> "PT0.2S");
  }

  @AfterAll
  static void stop() {
    ISSUER.close();
    TRACKER.close();
  }

  @Autowired private WebTestClient client;
  @Autowired private RepositoryRegistryService registry;
  @Autowired private StageRepository stages;
  @Autowired private FindingStateRepository states;

  @Autowired
  @Qualifier("egressZone")
  private WorkerZone egress;

  private void publish(String runId) {
    client
        .post()
        .uri("/api/v1/runs")
        .header("Authorization", "Bearer " + ISSUER.token(ZoneTestSupport.PIPELINE))
        .header("Content-Type", "application/json")
        .bodyValue(
            RunBundleBuilder.run()
                .id(runId)
                .repository("acme/egress-stopped")
                .commit(runId)
                .finding(
                    "fp-egress", "codemaat.hotspot", "HIGH", "HOTSPOT", "src/A.java", 40.0, null)
                .build())
        .exchange()
        .expectStatus()
        .isAccepted();
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> states.find("acme/egress-stopped", "default", "fp-egress").isPresent());
  }

  @Test
  void cpAc93_aStoppedEgressDeliversNothingAndLosesNothing() {
    egress.stop();
    ZoneTestSupport.onboard(registry, "acme/egress-stopped", "azure-boards", "Platform");
    ZoneTestSupport.enrol(stages, "acme/egress-stopped", RolloutStage.STAGE_3);

    // A finding is NEW the first time it is seen and OPEN the second; a work item follows OPEN.
    publish("e1");
    publish("e2");
    await()
        .atMost(Duration.ofSeconds(60))
        .until(
            () ->
                states
                    .find("acme/egress-stopped", "default", "fp-egress")
                    .map(s -> s.state() == FindingState.OPEN)
                    .orElse(false));

    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(TRACKER.requests).as("nothing delivered").isEmpty());

    egress.start();

    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(() -> assertThat(TRACKER.items).as("delivered").hasSize(1));
    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(TRACKER.items).as("once").hasSize(1));
    assertThat(TRACKER.items.values().iterator().next().project).isEqualTo("Platform");
  }
}
