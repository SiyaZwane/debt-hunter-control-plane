package com.debthunter.controlplane.ingest.normalise;

import java.util.Objects;

/**
 * A field the bundle did not carry and that was filled from {@link NormalisationDefaults}.
 *
 * @param field the field, written with {@code []} for array elements (for example {@code
 *     findings[].confidence})
 * @param occurrences how many times it was defaulted in this bundle
 */
public record DefaultedField(String field, int occurrences) {

  /** Validates the components. */
  public DefaultedField {
    Objects.requireNonNull(field, "field");
  }
}
