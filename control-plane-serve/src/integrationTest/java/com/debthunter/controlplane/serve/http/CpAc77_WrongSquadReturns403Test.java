package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.THING;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Severity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-77: a squad asking for another squad's data gets 403, on every endpoint. */
@Tag("integration")
class CpAc77_WrongSquadReturns403Test {

  @Test
  void anotherSquadsViewAndFindingsAre403() {
    try (ServeHarness h = new ServeHarness()) {
      h.run(
          "r1",
          WIDGETS,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"));
      String teamB = h.squad("team-b");

      for (String path :
          new String[] {
            "/api/v1/squads/team-a",
            "/api/v1/squads/team-a/findings",
            "/api/v1/squads/team-a/finding?repository=acme/widgets&run=r1&id=f-fp1",
            "/api/v1/findings?repository=acme/widgets"
          }) {
        h.get(teamB, path)
            .expectStatus()
            .isForbidden()
            .expectBody()
            .jsonPath("$.error")
            .isEqualTo("forbidden");
      }
    }
  }

  @Test
  void aSquadCannotReachAnotherSquadsRepositoryThroughItsOwnUrl() {
    try (ServeHarness h = new ServeHarness()) {
      h.run(
          "r3",
          THING,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp3", Severity.CRITICAL, "src/C.java"));

      h.get(h.squad("team-a"), "/api/v1/squads/team-a/findings?repository=other/thing")
          .expectStatus()
          .isForbidden();
      h.get(
              h.squad("team-a"),
              "/api/v1/squads/team-a/finding?repository=other/thing&run=r3&id=f-fp3")
          .expectStatus()
          .isForbidden();
    }
  }
}
