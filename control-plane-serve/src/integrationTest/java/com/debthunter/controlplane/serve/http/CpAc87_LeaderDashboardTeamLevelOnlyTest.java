package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.CONTRIBUTOR;
import static com.debthunter.controlplane.serve.http.ServeHarness.GADGETS;
import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-87: a leader sees the estate by team and nothing finer. The shape of the portfolio is
 * pinned to team-level fields, no request parameter can ask it for a finer grouping, and every
 * route that could show a repository, a finding or a person refuses a leader.
 */
@Tag("integration")
class CpAc87_LeaderDashboardTeamLevelOnlyTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static void seed(ServeHarness h) {
    h.run(
        "r1",
        WIDGETS,
        "1.0.0",
        NOW,
        FindingState.OPEN,
        ServeHarness.finding("fp1", Severity.HIGH, "src/Secret.java"));
    h.snapshot(WIDGETS, 0, 1, "FAILED", NOW.minusSeconds(30));
    h.snapshot(GADGETS, 1, 0, "PASSED", NOW.minusSeconds(30));
  }

  private static Set<String> names(JsonNode node) {
    Set<String> out = new TreeSet<>();
    node.fieldNames().forEachRemaining(out::add);
    return out;
  }

  private static JsonNode portfolio(ServeHarness h, String query) throws Exception {
    byte[] body =
        h.get(h.leader(), "/api/v1/portfolio" + query)
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();
    return JSON.readTree(body);
  }

  @Test
  void thePortfolioHasOnlyTeamLevelFields() throws Exception {
    try (ServeHarness h = new ServeHarness()) {
      seed(h);

      JsonNode view = portfolio(h, "");

      assertThat(names(view))
          .containsExactlyInAnyOrder(
              "generatedAt", "readModelsAsOf", "stale", "coverageAsOf", "teams");
      for (JsonNode team : view.get("teams")) {
        assertThat(names(team))
            .containsExactlyInAnyOrder(
                "team",
                "repositories",
                "findingsBySeverity",
                "repositoriesFailingPolicy",
                "coverage",
                "stages");
      }
    }
  }

  @Test
  void noParameterCanAskForAFinerGrouping() throws Exception {
    try (ServeHarness h = new ServeHarness()) {
      seed(h);
      JsonNode plain = portfolio(h, "");

      for (String query :
          new String[] {
            "?repository=" + WIDGETS,
            "?groupBy=repository",
            "?groupBy=contributor",
            "?team=team-a&detail=full",
            "?path=src/Secret.java"
          }) {
        JsonNode view = portfolio(h, query);

        assertThat(view.get("teams")).as(query).isEqualTo(plain.get("teams"));
        assertThat(view.toString())
            .as(query)
            .doesNotContain("acme/")
            .doesNotContain("src/Secret.java")
            .doesNotContain(CONTRIBUTOR);
      }
    }
  }

  @Test
  void everyRouteThatCouldShowARepositoryAFindingOrAPersonRefusesALeader() {
    try (ServeHarness h = new ServeHarness()) {
      seed(h);
      String leader = h.leader();

      for (String path :
          new String[] {
            "/api/v1/findings",
            "/api/v1/squads/team-a",
            "/api/v1/squads/team-a/findings",
            "/api/v1/squads/team-a/finding?repository=acme/widgets&run=r1&id=f-fp1",
            "/api/v1/evidence/runs/r1?purpose=looking%20around%20widgets",
            "/api/v1/audit/admin",
            "/api/v1/audit/evidence"
          }) {
        h.get(leader, path).expectStatus().isForbidden();
      }
      h.post(
              leader,
              "/api/v1/squads/team-a/suppressions",
              java.util.Map.of(
                  "repository",
                  WIDGETS,
                  "fingerprint",
                  "fp1",
                  "reason",
                  "a leader should not do this",
                  "expiresAt",
                  NOW.plusSeconds(86_400).toString()))
          .expectStatus()
          .isForbidden();
    }
  }
}
