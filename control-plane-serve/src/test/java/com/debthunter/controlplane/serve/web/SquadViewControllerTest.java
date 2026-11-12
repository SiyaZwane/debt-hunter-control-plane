package com.debthunter.controlplane.serve.web;

import static com.debthunter.controlplane.serve.ServeFixtures.CLOCK;
import static com.debthunter.controlplane.serve.ServeFixtures.GADGETS;
import static com.debthunter.controlplane.serve.ServeFixtures.NOW;
import static com.debthunter.controlplane.serve.ServeFixtures.THING;
import static com.debthunter.controlplane.serve.ServeFixtures.WIDGETS;
import static com.debthunter.controlplane.serve.ServeFixtures.row;

import com.debthunter.controlplane.analytics.coverage.InMemoryCoverageRepository;
import com.debthunter.controlplane.serve.ServeFixtures;
import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.serve.finding.FindingDetailService;
import com.debthunter.controlplane.serve.finding.FindingFixture;
import com.debthunter.controlplane.serve.query.InMemoryFindingQueryRepository;
import com.debthunter.controlplane.serve.squad.SquadViewService;
import com.debthunter.controlplane.store.findings.InMemoryPseudonymisedFindingStore;
import com.debthunter.controlplane.store.runs.InMemoryRunRepository;
import com.debthunter.domain.Severity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/** CP-AC-76 and CP-AC-77 at the HTTP edge. */
class SquadViewControllerTest {

  private final InMemoryFindingQueryRepository repo = new InMemoryFindingQueryRepository();
  private final InMemoryRunRepository runs = new InMemoryRunRepository();
  private final InMemoryPseudonymisedFindingStore store = new InMemoryPseudonymisedFindingStore();
  private SquadViewController controller;

  @BeforeEach
  void setUp() {
    controller =
        new SquadViewController(
            new SquadViewService(
                ServeFixtures.registry(),
                repo,
                new InMemoryCoverageRepository(),
                () -> java.util.Map.of(),
                new FindingDetailService(runs, store, FindingFixture.templatesOnly()),
                CLOCK));
    repo.asOf(NOW);
    repo.add(row(WIDGETS, "a1", Severity.HIGH));
    repo.add(row(GADGETS, "a2", Severity.LOW));
    repo.add(row(THING, "b1", Severity.CRITICAL));
  }

  private WebTestClient as(String subject, String squad) {
    return ControllerSupport.client(
        controller, ControllerSupport.token(subject, List.of(Roles.SQUAD), List.of(squad)));
  }

  @Test
  void aSquadSeesItsFindingsEachLinkedToItsDetailAndEvidence() {
    as("alice", "team-a")
        .get()
        .uri("/api/v1/squads/team-a/findings")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(2)
        .jsonPath("$.items[?(@.finding.repository=='other/thing')]")
        .isEmpty()
        .jsonPath("$.items[0].evidence")
        .isEqualTo("/api/v1/evidence/runs/run-a2")
        .jsonPath("$.items[0].detail")
        .isEqualTo("/api/v1/squads/team-a/finding?repository=acme%2Fgadgets&run=run-a2&id=f-a2")
        .jsonPath("$.asOf")
        .exists();
  }

  @Test
  void theOverviewIsServedToTheSquad() {
    as("alice", "team-a")
        .get()
        .uri("/api/v1/squads/team-a")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.squad")
        .isEqualTo("team-a")
        .jsonPath("$.repositories.length()")
        .isEqualTo(2);
  }

  @Test
  void anotherSquadsViewIs403OnEveryEndpoint() {
    WebTestClient b = as("bob", "team-b");
    for (String path :
        new String[] {
          "/api/v1/squads/team-a",
          "/api/v1/squads/team-a/findings",
          "/api/v1/squads/team-a/finding?repository=acme/widgets&run=r&id=f"
        }) {
      b.get()
          .uri(path)
          .exchange()
          .expectStatus()
          .isForbidden()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("forbidden");
    }
  }

  @Test
  void aFindingIsShownWithItsExplanationAndAMissingOneIs404() {
    runs.save(FindingFixture.run("r1", WIDGETS));
    store.saveRun("r1", WIDGETS, List.of(FindingFixture.finding()));
    String id = FindingFixture.finding().id();

    as("alice", "team-a")
        .get()
        .uri(
            b ->
                b.path("/api/v1/squads/team-a/finding")
                    .queryParam("repository", WIDGETS)
                    .queryParam("run", "r1")
                    .queryParam("id", id)
                    .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.finding.ruleId")
        .isEqualTo("codemaat.hotspot")
        .jsonPath("$.explanation.modelGenerated")
        .isEqualTo(false);
    as("alice", "team-a")
        .get()
        .uri(
            b ->
                b.path("/api/v1/squads/team-a/finding")
                    .queryParam("repository", WIDGETS)
                    .queryParam("run", "r1")
                    .queryParam("id", "nope")
                    .build())
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void badFiltersAre400() {
    as("alice", "team-a")
        .get()
        .uri("/api/v1/squads/team-a/findings?severity=SEVERE")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }
}
