package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.CONTRIBUTOR;
import static com.debthunter.controlplane.serve.http.ServeHarness.GADGETS;
import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.PURPOSE;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Severity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-11: no response of any read API carries a person — not a name, an email, a contributor
 * list, nor even the pseudonym that stands for one. Every route is called, as every role that may
 * call it, over data that does hold a contributor, and the whole body is searched.
 */
@Tag("integration")
class CpAc11_NoDashboardResponseContainsIdentityTest {

  private static final String EMAIL_SHAPED = "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}";

  private static void assertNoIdentity(String where, byte[] body) {
    String text = new String(body);
    assertThat(text).as(where).doesNotContain(CONTRIBUTOR);
    assertThat(text.toLowerCase())
        .as(where)
        .doesNotContain("contributor")
        .doesNotContain("author")
        .doesNotContain("committer")
        .doesNotContain("pseudonym");
    assertThat(text).as(where).doesNotContainPattern(EMAIL_SHAPED);
  }

  @Test
  void noReadRouteReturnsAPersonOrAPseudonym() {
    try (ServeHarness h = new ServeHarness()) {
      h.run(
          "r1",
          WIDGETS,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"),
          ServeHarness.finding("fp2", Severity.LOW, "src/B.java"));
      h.snapshot(WIDGETS, 0, 1, "FAILED", NOW.minusSeconds(30));
      h.snapshot(GADGETS, 1, 0, "PASSED", NOW.minusSeconds(30));
      String both =
          h.token(List.of("ControlPlane.Squad", "ControlPlane.Evidence"), List.of("team-a"));
      Map<String, String> calls =
          Map.of(
              h.leader(), "/api/v1/portfolio",
              h.squad("team-a"), "/api/v1/squads/team-a");
      calls.forEach(
          (token, path) ->
              assertNoIdentity(
                  path,
                  h.get(token, path)
                      .expectStatus()
                      .isOk()
                      .expectBody()
                      .returnResult()
                      .getResponseBody()));

      for (String path :
          new String[] {
            "/api/v1/squads/team-a/findings",
            "/api/v1/squads/team-a/finding?repository=" + WIDGETS + "&run=r1&id=f-fp1",
            "/api/v1/findings",
            "/api/v1/evidence/runs/r1?purpose=" + PURPOSE.replace(" ", "%20")
          }) {
        assertNoIdentity(
            path,
            h.get(both, path).expectStatus().isOk().expectBody().returnResult().getResponseBody());
      }
    }
  }
}
