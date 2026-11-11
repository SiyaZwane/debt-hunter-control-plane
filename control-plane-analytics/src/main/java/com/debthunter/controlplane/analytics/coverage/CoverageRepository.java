package com.debthunter.controlplane.analytics.coverage;

import java.util.Optional;

/** Where the latest coverage report is kept, so asking does not call the source-control host. */
public interface CoverageRepository {

  /** Replaces the stored report with a new one, atomically. */
  void replace(CoverageReport report);

  /** The latest report, if a reconciliation has ever completed. */
  Optional<CoverageReport> latest();
}
