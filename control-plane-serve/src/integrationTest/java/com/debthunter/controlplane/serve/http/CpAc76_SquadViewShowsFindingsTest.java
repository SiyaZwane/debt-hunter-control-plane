package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.GADGETS;
import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.PURPOSE;
import static com.debthunter.controlplane.serve.http.ServeHarness.THING;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Severity;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-76: a squad sees its own findings, and each leads on to its detail and its evidence. */
@Tag("integration")
class CpAc76_SquadViewShowsFindingsTest {

  @Test
  void theSquadSeesItsFindingsAndTheLinksWork() {
    try (ServeHarness h = new ServeHarness()) {
      h.bundle("1.0.0", "a", null);
      h.run(
          "r1",
          WIDGETS,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"));
      h.run(
          "r2",
          GADGETS,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp2", Severity.LOW, "src/B.java"));
      h.run(
          "r3",
          THING,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp3", Severity.CRITICAL, "src/C.java"));

      byte[] body =
          h.get(h.squad("team-a"), "/api/v1/squads/team-a/findings")
              .expectStatus()
              .isOk()
              .expectBody()
              .jsonPath("$.items.length()")
              .isEqualTo(2)
              .jsonPath("$.items[?(@.finding.repository=='other/thing')]")
              .isEmpty()
              .returnResult()
              .getResponseBody();
      String json = new String(body);
      String detail = JsonPath.read(json, "$.items[1].detail");
      String evidence = JsonPath.read(json, "$.items[1].evidence");
      assertThat(JsonPath.<String>read(json, "$.items[1].finding.repository")).isEqualTo(WIDGETS);

      h.get(h.squad("team-a"), detail)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.finding.ruleId")
          .isEqualTo("codemaat.hotspot")
          .jsonPath("$.explanation.modelGenerated")
          .isEqualTo(false)
          .jsonPath("$.explanation.text")
          .isNotEmpty();
      h.get(h.investigator("team-a"), evidence + "?purpose=" + PURPOSE.replace(" ", "%20"))
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.run.repository")
          .isEqualTo(WIDGETS);
    }
  }

  @Test
  void theOverviewShowsStageAndOpenCounts() {
    try (ServeHarness h = new ServeHarness()) {
      h.run(
          "r1",
          WIDGETS,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"));

      h.get(h.squad("team-a"), "/api/v1/squads/team-a")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.repositories.length()")
          .isEqualTo(2)
          .jsonPath("$.repositories[?(@.repository=='acme/widgets')].stage")
          .isEqualTo(java.util.List.of("STAGE_3"))
          .jsonPath("$.repositories[?(@.repository=='acme/widgets')].openBySeverity.HIGH")
          .isEqualTo(java.util.List.of(1));
    }
  }
}
