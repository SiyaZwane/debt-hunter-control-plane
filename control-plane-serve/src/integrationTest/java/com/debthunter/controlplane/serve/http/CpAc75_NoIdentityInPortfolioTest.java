package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.CONTRIBUTOR;
import static com.debthunter.controlplane.serve.http.ServeHarness.GADGETS;
import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Severity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-75: the portfolio shows teams, with no contributor and no repository. */
@Tag("integration")
class CpAc75_NoIdentityInPortfolioTest {

  @Test
  void aLeaderGetsTeamRowsThatNameNeitherAPersonNorARepository() {
    try (ServeHarness h = new ServeHarness()) {
      h.run(
          "r1",
          WIDGETS,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"));
      h.snapshot(WIDGETS, 0, 1, "FAILED", NOW.minusSeconds(30));
      h.snapshot(GADGETS, 1, 0, "PASSED", NOW.minusSeconds(30));

      byte[] body =
          h.get(h.leader(), "/api/v1/portfolio")
              .expectStatus()
              .isOk()
              .expectBody()
              .jsonPath("$.teams.length()")
              .isEqualTo(2)
              .jsonPath("$.teams[0].team")
              .isEqualTo("team-a")
              .jsonPath("$.teams[0].repositories")
              .isEqualTo(2)
              .jsonPath("$.teams[0].findingsBySeverity.HIGH")
              .isEqualTo(1)
              .jsonPath("$.teams[0].repositoriesFailingPolicy")
              .isEqualTo(1)
              .returnResult()
              .getResponseBody();

      String json = new String(body);
      assertThat(json)
          .doesNotContain(CONTRIBUTOR)
          .doesNotContain("acme")
          .doesNotContain("other/thing");
      assertThat(json.toLowerCase()).doesNotContain("contributor").doesNotContain("author");
    }
  }

  @Test
  void noTokenIs401() {
    try (ServeHarness h = new ServeHarness()) {
      h.get(null, "/api/v1/portfolio").expectStatus().isUnauthorized();
    }
  }
}
