package com.debthunter.controlplane.serve.query;

import static com.debthunter.controlplane.serve.ServeFixtures.NOW;
import static com.debthunter.controlplane.serve.ServeFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What every finding query repository must do, over the same data. */
public abstract class FindingQueryRepositoryContract {

  protected abstract FindingQueryRepository newRepository();

  /** Puts findings where the repository reads from. */
  protected abstract void seed(FindingRow... rows);

  protected FindingQueryRepository repo;

  @BeforeEach
  void setUp() {
    repo = newRepository();
  }

  private static FindingFilter in(String... repositories) {
    return new FindingFilter(Set.of(repositories), null, null, null, null, null);
  }

  private static PageRequest first() {
    return PageRequest.of(null, null);
  }

  private static List<String> fingerprints(Page<FindingRow> page) {
    return page.items().stream().map(FindingRow::fingerprint).toList();
  }

  @Test
  void findingsComeBackInRepositoryThenFingerprintOrderWithEveryFieldIntact() {
    seed(
        row("acme/b", "z", Severity.LOW),
        row("acme/a", "y", Severity.HIGH),
        row("acme/a", "x", Severity.HIGH));

    Page<FindingRow> page = repo.find(in("acme/a", "acme/b"), first());

    assertThat(fingerprints(page)).containsExactly("x", "y", "z");
    assertThat(page.items().get(0)).isEqualTo(row("acme/a", "x", Severity.HIGH));
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void onlyTheNamedRepositoriesAreLookedIn() {
    seed(row("acme/a", "x", Severity.HIGH), row("other/b", "y", Severity.HIGH));

    assertThat(fingerprints(repo.find(in("acme/a"), first()))).containsExactly("x");
    assertThat(repo.find(in("nobody/here"), first()).items()).isEmpty();
  }

  @Test
  void eachFilterNarrows() {
    seed(
        row("acme/a", "1", Severity.HIGH, FindingState.OPEN, NOW),
        row("acme/a", "2", Severity.LOW, FindingState.RESOLVED, NOW.minus(Duration.ofDays(10))),
        row("acme/a", "3", Severity.LOW, FindingState.REGRESSED, NOW.minus(Duration.ofDays(2))));
    Set<String> a = Set.of("acme/a");

    assertThat(
            fingerprints(
                repo.find(new FindingFilter(a, Severity.LOW, null, null, null, null), first())))
        .containsExactly("2", "3");
    assertThat(
            fingerprints(
                repo.find(new FindingFilter(a, null, Category.HOTSPOT, null, null, null), first())))
        .containsExactly("1", "2", "3");
    assertThat(
            fingerprints(
                repo.find(new FindingFilter(a, null, Category.CHURN, null, null, null), first())))
        .isEmpty();
    assertThat(
            fingerprints(
                repo.find(
                    new FindingFilter(a, null, null, FindingState.RESOLVED, null, null), first())))
        .containsExactly("2");
    assertThat(
            fingerprints(
                repo.find(
                    new FindingFilter(a, null, null, null, NOW.minus(Duration.ofDays(3)), null),
                    first())))
        .containsExactly("1", "3");
    assertThat(
            fingerprints(
                repo.find(
                    new FindingFilter(a, null, null, null, null, NOW.minus(Duration.ofDays(3))),
                    first())))
        .containsExactly("2");
    assertThat(
            fingerprints(
                repo.find(
                    new FindingFilter(a, Severity.LOW, null, FindingState.REGRESSED, null, null),
                    first())))
        .containsExactly("3");
  }

  @Test
  void pagesFollowEachOtherByCursorWithoutRepeatingOrSkipping() {
    List<FindingRow> rows = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      rows.add(row("acme/a", "f" + i, Severity.MEDIUM));
    }
    seed(rows.toArray(FindingRow[]::new));

    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      Page<FindingRow> page = repo.find(in("acme/a"), new PageRequest(4, cursor));
      seen.addAll(fingerprints(page));
      cursor = page.nextCursor();
      pages++;
    } while (cursor != null);

    assertThat(seen).containsExactly("f0", "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8");
    assertThat(pages).isEqualTo(3);
  }

  @Test
  void aPageThatEndsExactlyOnTheLastItemHasNoCursor() {
    seed(row("acme/a", "1", Severity.HIGH), row("acme/a", "2", Severity.HIGH));

    Page<FindingRow> page = repo.find(in("acme/a"), new PageRequest(2, null));

    assertThat(page.items()).hasSize(2);
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void onlyNewOpenAndRegressedFindingsAreCountedAsOpen() {
    seed(
        row("acme/a", "1", Severity.HIGH, FindingState.OPEN, NOW),
        row("acme/a", "2", Severity.HIGH, FindingState.NEW, NOW),
        row("acme/a", "3", Severity.LOW, FindingState.REGRESSED, NOW),
        row("acme/a", "4", Severity.CRITICAL, FindingState.RESOLVED, NOW),
        row("acme/a", "5", Severity.CRITICAL, FindingState.SUPPRESSED, NOW),
        row("other/b", "6", Severity.HIGH, FindingState.OPEN, NOW));

    var counts = repo.openCounts(Set.of("acme/a", "nobody/here"));

    assertThat(counts).containsOnlyKeys("acme/a");
    assertThat(counts.get("acme/a"))
        .containsOnly(java.util.Map.entry(Severity.HIGH, 2), java.util.Map.entry(Severity.LOW, 1));
    assertThat(repo.openCounts(Set.of())).isEmpty();
  }

  @Test
  void withNoDataThereIsNothingToBeAsOf() {
    assertThat(repo.asOf()).isEmpty();
  }
}
