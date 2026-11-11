package com.debthunter.controlplane.store.suppressions;

import java.util.List;
import java.util.Optional;

/** Persistence for suppressions. At most one per finding. */
public interface SuppressionRepository {

  /** Inserts a suppression, replacing any existing one for the same finding. */
  void save(Suppression suppression);

  /** Looks up the suppression on a finding. */
  Optional<Suppression> find(String repository, String fingerprint);

  /** Removes a suppression by id; returns whether it existed. */
  boolean delete(String id);

  /** Suppressions whose owner has not yet been warned. */
  List<Suppression> findUnnotified();

  /** Records that the owner was warned. */
  void markNotified(String id, java.time.Instant at);
}
