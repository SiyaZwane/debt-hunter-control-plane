package com.debthunter.controlplane.store.identity;

import java.util.Optional;

/**
 * The only place a pseudonym can be traced back to a person.
 *
 * <p>This is deliberately separate from every other store, and the only path to it for reading is
 * the audited {@code ReIdentificationService}. A production implementation must encrypt at rest,
 * restrict access to that service alone, and be excluded from every export and backup that leaves
 * the deployment.
 */
public interface PseudonymDirectory {

  /**
   * Records which identity a pseudonym stands for. Idempotent: the first identity recorded for a
   * pseudonym is kept.
   *
   * @param pseudonym the pseudonym
   * @param identity the normalised identity it was derived from
   */
  void remember(Pseudonym pseudonym, String identity);

  /**
   * Looks up the identity behind a pseudonym.
   *
   * @param pseudonym the pseudonym
   * @return the identity, or empty if the pseudonym is unknown
   * @throws PseudonymDirectoryException if it is known but the stored identity cannot be opened
   */
  Optional<String> lookup(Pseudonym pseudonym);
}
