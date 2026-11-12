package com.debthunter.controlplane.store.suppressions;

import java.util.List;
import java.util.Optional;

/** Persistence for suppressions. At most one per finding. */
public interface SuppressionRepository {

  /** Inserts a suppression, replacing any existing one for the same finding. */
  void save(Suppression suppression);

  /** Looks up the suppression on a finding. */
  Optional<Suppression> find(String repository, String fingerprint);

  /** Looks up a suppression by id. */
  Optional<Suppression> findById(String id);

  /** Removes a suppression by id; returns whether it existed. */
  boolean delete(String id);

  /**
   * Suppressions that lapse in a window.
   *
   * @param from the start, inclusive
   * @param to the end, exclusive
   * @return those whose expiry falls in the window, soonest first
   */
  List<Suppression> findExpiring(java.time.Instant from, java.time.Instant to);

  /** Suppressions whose owner has not yet been warned. */
  List<Suppression> findUnnotified();

  /** Records that the owner was warned. */
  void markNotified(String id, java.time.Instant at);
}
