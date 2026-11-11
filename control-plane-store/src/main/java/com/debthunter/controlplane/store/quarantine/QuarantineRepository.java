package com.debthunter.controlplane.store.quarantine;

import java.util.List;

/** Persistence for bundles set aside. */
public interface QuarantineRepository {

  /** Records a bundle. Recording the same correlation id again is a no-op (redelivery). */
  void save(QuarantinedBundle bundle);

  /** Every bundle set aside for a run id, oldest first. */
  List<QuarantinedBundle> forRun(String runId);

  /** How many bundles are set aside. */
  long count();
}
