package com.debthunter.controlplane.ingest.pseudonymise;

import com.debthunter.controlplane.store.identity.Pseudonym;
import java.util.Objects;
import java.util.Set;

/**
 * A request to learn who a pseudonym stands for.
 *
 * @param caller who is asking, as authenticated
 * @param authorities what the caller is authorised to do
 * @param pseudonym whom they want to identify
 * @param justification why, in the caller's own words; recorded in the audit trail
 */
public record ReIdentificationRequest(
    String caller, Set<String> authorities, Pseudonym pseudonym, String justification) {

  /** Validates and defensively copies the components. */
  public ReIdentificationRequest {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(pseudonym, "pseudonym");
    authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
  }
}
