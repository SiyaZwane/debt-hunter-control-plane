package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.THING;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;

import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Severity;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-80: leaders see teams only; squads see their own findings only; neither reaches the
 * other's.
 */
@Tag("integration")
class CpAc80_LeaderAndSquadScopedCorrectlyTest {

  private static void seed(ServeHarness h) {
    h.run(
        "r1",
        WIDGETS,
        "1.0.0",
        NOW,
        FindingState.OPEN,
        ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"));
    h.run(
        "r3",
        THING,
        "1.0.0",
        NOW,
        FindingState.OPEN,
        ServeHarness.finding("fp3", Severity.CRITICAL, "src/C.java"));
    h.snapshot(WIDGETS, 0, 1, "PASSED", NOW);
  }

  @Test
  void aLeaderSeesThePortfolioButNoSquadsFindingsOrEvidence() {
    try (ServeHarness h = new ServeHarness()) {
      seed(h);
      String leader = h.leader();

      h.get(leader, "/api/v1/portfolio").expectStatus().isOk();
      h.get(leader, "/api/v1/findings").expectStatus().isForbidden();
      h.get(leader, "/api/v1/squads/team-a").expectStatus().isForbidden();
      h.get(leader, "/api/v1/squads/team-a/findings").expectStatus().isForbidden();
      h.get(leader, "/api/v1/evidence/runs/r1?purpose=" + "looking%20around%20widgets")
          .expectStatus()
          .isForbidden();
    }
  }

  @Test
  void aSquadMemberSeesItsOwnFindingsButNotThePortfolio() {
    try (ServeHarness h = new ServeHarness()) {
      seed(h);
      String squad = h.squad("team-a");

      h.get(squad, "/api/v1/squads/team-a").expectStatus().isOk();
      h.get(squad, "/api/v1/findings")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.items.length()")
          .isEqualTo(1);
      h.get(squad, "/api/v1/portfolio").expectStatus().isForbidden();
    }
  }

  @Test
  void someoneWhoIsBothSeesBothButOnlyTheirOwnSquadsFindings() {
    try (ServeHarness h = new ServeHarness()) {
      seed(h);
      String both = h.token(List.of(Roles.LEADER, Roles.SQUAD), List.of("team-a"));

      h.get(both, "/api/v1/portfolio")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.teams.length()")
          .isEqualTo(2);
      h.get(both, "/api/v1/findings")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.items.length()")
          .isEqualTo(1)
          .jsonPath("$.items[0].repository")
          .isEqualTo(WIDGETS);
      h.get(both, "/api/v1/squads/team-b").expectStatus().isForbidden();
    }
  }

  @Test
  void aTokenWithNoRolesGetsNothing() {
    try (ServeHarness h = new ServeHarness()) {
      seed(h);
      String empty = h.token(List.of(), List.of("team-a"));

      for (String path :
          new String[] {"/api/v1/portfolio", "/api/v1/findings", "/api/v1/squads/team-a"}) {
        h.get(empty, path).expectStatus().isForbidden();
      }
    }
  }
}
