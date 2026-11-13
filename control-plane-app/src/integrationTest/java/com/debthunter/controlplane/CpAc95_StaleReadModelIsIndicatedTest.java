package com.debthunter.controlplane;

import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * CP-AC-95: when the read models are stale the portfolio says so. The whole application: the
 * portfolio is fresh while the projector runs, and once it is stopped and the threshold passes the
 * same endpoint labels its numbers stale rather than hiding them or presenting them as current.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CpAc95_StaleReadModelIsIndicatedTest {

  private static final TestJwtIssuer ISSUER = new TestJwtIssuer();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    ZoneTestSupport.baseline(registry, ISSUER);
    registry.add("debthunter.controlplane.analytics.projection-interval", () -> "PT0.2S");
    registry.add("debthunter.controlplane.analytics.stale-after", () -> "PT2S");
  }

  @AfterAll
  static void stopIssuer() {
    ISSUER.close();
  }

  @Autowired private WebTestClient client;
  @Autowired private ProjectorLifecycle projector;

  private Boolean stale() {
    return client
        .get()
        .uri("/api/v1/portfolio")
        .header("Authorization", "Bearer " + ISSUER.tokenWithRoles("leader", "ControlPlane.Leader"))
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult(java.util.Map.class)
        .getResponseBody()
        .map(body -> (Boolean) body.get("stale"))
        .blockFirst();
  }

  @Test
  void cpAc95_thePortfolioIsLabelledStaleOnceTheProjectorHasStopped() {
    await().atMost(Duration.ofSeconds(30)).until(() -> Boolean.FALSE.equals(stale()));

    projector.stop();

    await().atMost(Duration.ofSeconds(30)).until(() -> Boolean.TRUE.equals(stale()));
  }
}
