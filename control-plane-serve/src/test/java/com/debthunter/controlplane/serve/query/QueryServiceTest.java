package com.debthunter.controlplane.serve.query;

import static com.debthunter.controlplane.serve.ServeFixtures.CLOCK;
import static com.debthunter.controlplane.serve.ServeFixtures.GADGETS;
import static com.debthunter.controlplane.serve.ServeFixtures.NOW;
import static com.debthunter.controlplane.serve.ServeFixtures.THING;
import static com.debthunter.controlplane.serve.ServeFixtures.WIDGETS;
import static com.debthunter.controlplane.serve.ServeFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.serve.ServeFixtures;
import com.debthunter.controlplane.serve.access.AccessDeniedException;
import com.debthunter.controlplane.serve.access.InvalidRequestException;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CP-AC-78 and the rules around it: scoped, filtered, paged, fresh, and free of identity. */
class QueryServiceTest {

  private final InMemoryFindingQueryRepository repo = new InMemoryFindingQueryRepository();
  private QueryService service;

  @BeforeEach
  void setUp() {
    service = new QueryService(repo, ServeFixtures.registry(), CLOCK);
    repo.asOf(NOW.minusSeconds(300));
    repo.add(row(WIDGETS, "a1", Severity.HIGH));
    repo.add(row(WIDGETS, "a2", Severity.LOW));
    repo.add(
        row(
            GADGETS,
            "a3",
            Severity.CRITICAL,
            FindingState.RESOLVED,
            NOW.minus(Duration.ofDays(10))));
    repo.add(row(THING, "b1", Severity.HIGH));
  }

  private List<String> fingerprints(QueryResult r) {
    return r.items().stream().map(FindingRow::fingerprint).toList();
  }

  @Test
  void aSquadSeesItsOwnRepositoriesAndNoOthers() {
    QueryResult result = service.query(ServeFixtures.squadA(), QueryParams.all());

    assertThat(fingerprints(result)).containsExactly("a3", "a1", "a2");
    assertThat(result.items()).noneMatch(r -> r.repository().equals(THING));
  }

  @Test
  void everyResponseCarriesWhenItsDataIsAsOfAndWhenItWasMade() {
    QueryResult result = service.query(ServeFixtures.squadA(), QueryParams.all());

    assertThat(result.asOf()).isEqualTo(NOW.minusSeconds(300));
    assertThat(result.generatedAt()).isEqualTo(NOW);
  }

  @Test
  void aCallerWithNoSquadReposStillGetsFreshnessOnAnEmptyAnswer() {
    com.debthunter.controlplane.serve.access.Caller lonely =
        new com.debthunter.controlplane.serve.access.Caller(
            "lone",
            java.util.Set.of(com.debthunter.controlplane.serve.access.Roles.SQUAD),
            java.util.Set.of("team-z"));

    QueryResult result = service.query(lonely, QueryParams.all());

    assertThat(result.items()).isEmpty();
    assertThat(result.generatedAt()).isEqualTo(NOW);
    assertThat(result.asOf()).isEqualTo(NOW.minusSeconds(300));
  }

  @Test
  void filtersNarrowByRepositorySeverityCategoryStateAndTime() {
    var a = ServeFixtures.squadA();

    assertThat(
            fingerprints(
                service.query(
                    a, new QueryParams(GADGETS, null, null, null, null, null, null, null))))
        .containsExactly("a3");
    assertThat(
            fingerprints(
                service.query(
                    a, new QueryParams(null, Severity.HIGH, null, null, null, null, null, null))))
        .containsExactly("a1");
    assertThat(
            fingerprints(
                service.query(
                    a,
                    new QueryParams(
                        null, null, Category.TEST_HEALTH, null, null, null, null, null))))
        .isEmpty();
    assertThat(
            fingerprints(
                service.query(
                    a,
                    new QueryParams(
                        null, null, null, FindingState.RESOLVED, null, null, null, null))))
        .containsExactly("a3");
    assertThat(
            fingerprints(
                service.query(
                    a,
                    new QueryParams(
                        null, null, null, null, NOW.minus(Duration.ofDays(1)), null, null, null))))
        .containsExactly("a1", "a2");
    assertThat(
            fingerprints(
                service.query(
                    a,
                    new QueryParams(
                        null, null, null, null, null, NOW.minus(Duration.ofDays(1)), null, null))))
        .containsExactly("a3");
  }

  @Test
  void pagesFollowEachOtherWithoutRepeatingOrSkipping() {
    for (int i = 0; i < 7; i++) {
      repo.add(row(WIDGETS, "p" + i, Severity.MEDIUM));
    }
    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      QueryResult page =
          service.query(
              ServeFixtures.squadA(),
              new QueryParams(null, null, null, null, null, null, 4, cursor));
      assertThat(page.items()).hasSizeLessThanOrEqualTo(4);
      seen.addAll(fingerprints(page));
      cursor = page.nextCursor();
      pages++;
    } while (cursor != null);

    assertThat(seen).hasSize(10).doesNotHaveDuplicates();
    assertThat(pages).isEqualTo(3);
  }

  @Test
  void aFindingAppearingBetweenPagesNeitherRepeatsNorSkipsAnother() {
    var a = ServeFixtures.squadA();
    QueryResult first =
        service.query(a, new QueryParams(null, null, null, null, null, null, 2, null));
    repo.add(row(WIDGETS, "a0", Severity.HIGH)); // sorts before the cursor
    repo.add(row(WIDGETS, "zz", Severity.HIGH)); // sorts after it

    QueryResult second =
        service.query(
            a, new QueryParams(null, null, null, null, null, null, 2, first.nextCursor()));

    assertThat(fingerprints(first)).containsExactly("a3", "a1");
    assertThat(fingerprints(second)).containsExactly("a2", "zz");
  }

  @Test
  void aRepositoryOutsideTheCallersSquadsIsRefusedWhetherOrNotItExists() {
    var a = ServeFixtures.squadA();

    assertThatThrownBy(
            () ->
                service.query(a, new QueryParams(THING, null, null, null, null, null, null, null)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                service.query(
                    a, new QueryParams("ghost/repo", null, null, null, null, null, null, null)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void onlyCallersWithTheSquadRoleMayQuery() {
    for (var caller :
        List.of(ServeFixtures.leader(), ServeFixtures.investigatorA(), ServeFixtures.nobody())) {
      assertThatThrownBy(() -> service.query(caller, QueryParams.all()))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void badPagingAndWindowsAreRefused() {
    assertThatThrownBy(() -> new QueryParams(null, null, null, null, NOW, NOW, null, null))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> PageRequest.of(0, null)).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> PageRequest.of(201, null)).isInstanceOf(InvalidRequestException.class);
    assertThat(PageRequest.of(null, null).limit()).isEqualTo(50);
    assertThatThrownBy(() -> Cursor.decode("not-a-cursor!!"))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> Cursor.decode("YQ")).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void aFindingRowHasNoFieldThatCouldHoldAPerson() {
    assertThat(Arrays.stream(FindingRow.class.getRecordComponents()).map(RecordComponent::getName))
        .noneMatch(
            n -> {
              String l = n.toLowerCase();
              return l.contains("contributor")
                  || l.contains("author")
                  || l.contains("committer")
                  || l.contains("email")
                  || l.contains("pseudonym")
                  || l.contains("owner");
            });
  }
}
