package com.debthunter.controlplane.serve.query;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Severity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Answers queries over rows it is given. For tests and for running without a database. */
public final class InMemoryFindingQueryRepository implements FindingQueryRepository {

  private static final Comparator<FindingRow> ORDER =
      Comparator.comparing(FindingRow::repository)
          .thenComparing(FindingRow::projectSlice)
          .thenComparing(FindingRow::fingerprint);

  private final List<FindingRow> rows = new ArrayList<>();
  private Instant asOf;

  /** Adds a finding. */
  public synchronized void add(FindingRow row) {
    rows.add(row);
  }

  /** Sets the time the data is said to reflect. */
  public synchronized void asOf(Instant at) {
    this.asOf = at;
  }

  @Override
  public synchronized Page<FindingRow> find(FindingFilter f, PageRequest page) {
    Cursor after = page.cursor() == null ? null : Cursor.decode(page.cursor());
    List<FindingRow> matching =
        rows.stream()
            .filter(r -> f.repositories().contains(r.repository()))
            .filter(r -> f.severity() == null || r.severity() == f.severity())
            .filter(r -> f.category() == null || r.category() == f.category())
            .filter(r -> f.state() == null || r.state() == f.state())
            .filter(
                r ->
                    f.observedFrom() == null
                        || (r.observedAt() != null && !r.observedAt().isBefore(f.observedFrom())))
            .filter(
                r ->
                    f.observedTo() == null
                        || (r.observedAt() != null && r.observedAt().isBefore(f.observedTo())))
            .filter(r -> after == null || after.compareTo(r) < 0)
            .sorted(ORDER)
            .limit(page.limit() + 1L)
            .toList();
    boolean more = matching.size() > page.limit();
    List<FindingRow> items = more ? matching.subList(0, page.limit()) : matching;
    return new Page<>(items, more ? Cursor.after(items.get(items.size() - 1)).encode() : null);
  }

  @Override
  public synchronized Map<String, Map<Severity, Integer>> openCounts(
      Collection<String> repositories) {
    Map<String, Map<Severity, Integer>> counts = new HashMap<>();
    for (FindingRow r : rows) {
      boolean open =
          r.state() == FindingState.NEW
              || r.state() == FindingState.OPEN
              || r.state() == FindingState.REGRESSED;
      if (open && repositories.contains(r.repository())) {
        counts
            .computeIfAbsent(r.repository(), k -> new EnumMap<>(Severity.class))
            .merge(r.severity(), 1, Integer::sum);
      }
    }
    return counts;
  }

  @Override
  public synchronized Optional<Instant> asOf() {
    return Optional.ofNullable(asOf);
  }
}
