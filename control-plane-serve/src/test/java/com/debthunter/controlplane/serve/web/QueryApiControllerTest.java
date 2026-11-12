package com.debthunter.controlplane.serve.web;

import static com.debthunter.controlplane.serve.ServeFixtures.CLOCK;
import static com.debthunter.controlplane.serve.ServeFixtures.GADGETS;
import static com.debthunter.controlplane.serve.ServeFixtures.NOW;
import static com.debthunter.controlplane.serve.ServeFixtures.THING;
import static com.debthunter.controlplane.serve.ServeFixtures.WIDGETS;
import static com.debthunter.controlplane.serve.ServeFixtures.row;

import com.debthunter.controlplane.serve.ServeFixtures;
import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.serve.query.InMemoryFindingQueryRepository;
import com.debthunter.controlplane.serve.query.QueryService;
import com.debthunter.domain.Severity;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/** CP-AC-78 at the HTTP edge: parsing, errors, paging and freshness. */
class QueryApiControllerTest {

  private final InMemoryFindingQueryRepository repo = new InMemoryFindingQueryRepository();
  private QueryApiController controller;

  @BeforeEach
  void setUp() {
    controller = new QueryApiController(new QueryService(repo, ServeFixtures.registry(), CLOCK));
    repo.asOf(NOW.minusSeconds(60));
    repo.add(row(WIDGETS, "a1", Severity.HIGH));
    repo.add(row(WIDGETS, "a2", Severity.LOW));
    repo.add(row(GADGETS, "a3", Severity.CRITICAL));
    repo.add(row(THING, "b1", Severity.HIGH));
  }

  private WebTestClient asSquadA() {
    return ControllerSupport.client(
        controller, ControllerSupport.token("alice", List.of(Roles.SQUAD), List.of("team-a")));
  }

  @Test
  void aResponseCarriesItsFreshnessAndOnlyTheCallersFindings() {
    asSquadA()
        .get()
        .uri("/api/v1/findings")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(3)
        .jsonPath("$.asOf")
        .exists()
        .jsonPath("$.generatedAt")
        .exists()
        .jsonPath("$.items[?(@.repository=='other/thing')]")
        .isEmpty();
  }

  @Test
  void filtersAreReadFromTheQueryString() {
    asSquadA()
        .get()
        .uri("/api/v1/findings?severity=high&state=OPEN&category=hotspot&repository=acme/widgets")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(1)
        .jsonPath("$.items[0].fingerprint")
        .isEqualTo("a1");
  }

  @Test
  void timeWindowsAreIsoInstants() {
    asSquadA()
        .get()
        .uri("/api/v1/findings?from=" + NOW.minus(Duration.ofDays(1)) + "&to=" + NOW.plusSeconds(1))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(3);
  }

  @Test
  void pagingReturnsACursorThatLeadsToTheNextPage() {
    byte[] first =
        asSquadA()
            .get()
            .uri("/api/v1/findings?limit=2")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.items.length()")
            .isEqualTo(2)
            .jsonPath("$.nextCursor")
            .isNotEmpty()
            .returnResult()
            .getResponseBody();
    String cursor = new String(first).replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

    asSquadA()
        .get()
        .uri("/api/v1/findings?limit=2&cursor=" + cursor)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(1)
        .jsonPath("$.nextCursor")
        .doesNotExist();
  }

  @Test
  void aRepositoryOutsideTheCallersSquadsIs403() {
    asSquadA()
        .get()
        .uri("/api/v1/findings?repository=other/thing")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("forbidden");
  }

  @Test
  void aCallerWithoutTheSquadRoleIs403() {
    ControllerSupport.client(
            controller, ControllerSupport.token("lena", List.of(Roles.LEADER), List.of()))
        .get()
        .uri("/api/v1/findings")
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  void aTokenWithNoRolesOrSquadsClaimIsAnonymousAndRefused() {
    ControllerSupport.client(controller, ControllerSupport.token("who", null, null))
        .get()
        .uri("/api/v1/findings")
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  void badParametersAre400WithAStableCode() {
    for (String bad :
        new String[] {
          "severity=SEVERE",
          "category=nope",
          "state=gone",
          "from=yesterday",
          "to=2026-13-45",
          "limit=ten",
          "limit=0",
          "limit=201",
          "cursor=%25%25%25",
          "from=2026-07-02T00:00:00Z&to=2026-07-01T00:00:00Z"
        }) {
      asSquadA()
          .get()
          .uri("/api/v1/findings?" + bad)
          .exchange()
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("invalid_request");
    }
  }
}
