package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.CONTRIBUTOR;
import static com.debthunter.controlplane.serve.http.ServeHarness.GADGETS;
import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Severity;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-78: every query response says how fresh it is, and carries no identity. */
@Tag("integration")
class CpAc78_QueryApiHasFreshnessTimestampTest {

  private static void seed(ServeHarness h) {
    h.run(
        "r1",
        WIDGETS,
        "1.0.0",
        NOW,
        FindingState.OPEN,
        ServeHarness.finding("a1", Severity.HIGH, "src/A.java"),
        ServeHarness.finding("a2", Severity.LOW, "src/B.java"),
        ServeHarness.finding("a3", Severity.HIGH, "src/C.java"));
    h.run(
        "r2",
        GADGETS,
        "1.0.0",
        NOW.minusSeconds(86_400 * 3L),
        FindingState.RESOLVED,
        ServeHarness.finding("g1", Severity.CRITICAL, "src/G.java"));
  }

  @Test
  void everyResponseCarriesWhenItsDataIsAsOfAndWhenItWasMade() {
    try (ServeHarness h = new ServeHarness()) {
      seed(h);

      h.get(h.squad("team-a"), "/api/v1/findings")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.asOf")
          .isNotEmpty()
          .jsonPath("$.generatedAt")
          .isNotEmpty()
          .jsonPath("$.items.length()")
          .isEqualTo(4);
      h.get(h.squad("team-a"), "/api/v1/findings?repository=acme/gadgets&state=resolved")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.generatedAt")
          .isNotEmpty();
    }
  }

  @Test
  void filtersAndPagingWorkOverHttp() {
    try (ServeHarness h = new ServeHarness()) {
      seed(h);

      byte[] page1 =
          h.get(h.squad("team-a"), "/api/v1/findings?severity=high&limit=1")
              .expectStatus()
              .isOk()
              .expectBody()
              .jsonPath("$.items.length()")
              .isEqualTo(1)
              .jsonPath("$.nextCursor")
              .isNotEmpty()
              .returnResult()
              .getResponseBody();
      String cursor = JsonPath.read(new String(page1), "$.nextCursor");
      h.get(h.squad("team-a"), "/api/v1/findings?severity=high&limit=1&cursor=" + cursor)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.items[0].fingerprint")
          .isEqualTo("a3")
          .jsonPath("$.nextCursor")
          .doesNotExist();
      h.get(h.squad("team-a"), "/api/v1/findings?from=2026-07-01T00:00:00Z")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.items.length()")
          .isEqualTo(3);
    }
  }

  @Test
  void noResponseCarriesAContributor() {
    try (ServeHarness h = new ServeHarness()) {
      seed(h);

      String body =
          new String(
              h.get(h.squad("team-a"), "/api/v1/findings")
                  .expectStatus()
                  .isOk()
                  .expectBody()
                  .returnResult()
                  .getResponseBody());

      assertThat(body).doesNotContain(CONTRIBUTOR).doesNotContainIgnoringCase("contributor");
    }
  }

  @Test
  void badInputIs400AndNoTokenIs401() {
    try (ServeHarness h = new ServeHarness()) {
      h.get(h.squad("team-a"), "/api/v1/findings?severity=SEVERE").expectStatus().isBadRequest();
      h.get(h.squad("team-a"), "/api/v1/findings?limit=500").expectStatus().isBadRequest();
      h.get(null, "/api/v1/findings").expectStatus().isUnauthorized();
    }
  }
}
