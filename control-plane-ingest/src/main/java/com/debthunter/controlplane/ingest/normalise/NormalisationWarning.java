package com.debthunter.controlplane.ingest.normalise;

import java.util.Objects;

/**
 * Something that was approximated rather than carried over exactly.
 *
 * @param field the field, written with {@code []} for array elements (for example {@code
 *     findings[].category})
 * @param message what was approximated and how
 * @param occurrences how many times it happened in this bundle
 */
public record NormalisationWarning(String field, String message, int occurrences) {

  /** Validates the components. */
  public NormalisationWarning {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(message, "message");
  }
}
