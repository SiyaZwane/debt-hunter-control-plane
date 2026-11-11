package com.debthunter.controlplane.analytics.coverage;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * A {@link CoverageRepository} held in memory, for tests and local development. Like the PostgreSQL
 * one it returns repositories ordered by identifier, ignoring case.
 */
public final class InMemoryCoverageRepository implements CoverageRepository {

  private CoverageReport report;

  @Override
  public synchronized void replace(CoverageReport newReport) {
    this.report =
        new CoverageReport(
            newReport.reconciledAt(),
            newReport.estateAvailable(),
            newReport.repositories().stream()
                .sorted(
                    Comparator.comparing(
                            (RepositoryCoverage c) -> c.repository().toLowerCase(Locale.ROOT))
                        .thenComparing(RepositoryCoverage::repository))
                .toList(),
            newReport.warnings());
  }

  @Override
  public synchronized Optional<CoverageReport> latest() {
    return Optional.ofNullable(report);
  }
}
