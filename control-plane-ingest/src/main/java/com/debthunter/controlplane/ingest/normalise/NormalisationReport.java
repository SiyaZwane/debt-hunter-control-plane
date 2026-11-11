package com.debthunter.controlplane.ingest.normalise;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What normalisation had to do beyond copying: the fields it defaulted and the values it
 * approximated. Entries are aggregated by field, so the size is bounded by the schema rather than
 * by the number of findings.
 *
 * @param defaultedFields fields filled from {@link NormalisationDefaults}
 * @param warnings values approximated rather than carried over
 */
public record NormalisationReport(
    List<DefaultedField> defaultedFields, List<NormalisationWarning> warnings) {

  /** Copies the lists defensively. */
  public NormalisationReport {
    defaultedFields = List.copyOf(defaultedFields);
    warnings = List.copyOf(warnings);
  }

  /**
   * Whether nothing was defaulted and nothing approximated.
   *
   * @return true for a bundle that mapped across exactly
   */
  public boolean isClean() {
    return defaultedFields.isEmpty() && warnings.isEmpty();
  }

  /**
   * Whether a field was defaulted.
   *
   * @param field the field, as written in {@link DefaultedField#field()}
   * @return true if it was defaulted at least once
   */
  public boolean wasDefaulted(String field) {
    return defaultedFields.stream().anyMatch(d -> d.field().equals(field));
  }

  /** Collects the entries as a bundle is mapped. Not thread-safe; one per normalisation. */
  public static final class Builder {

    private final Map<String, Integer> defaults = new LinkedHashMap<>();
    private final Map<String, NormalisationWarning> warnings = new LinkedHashMap<>();

    /**
     * Records that a field was filled from a default.
     *
     * @param field the field, written with {@code []} for array elements
     */
    public void defaulted(String field) {
      defaults.merge(field, 1, Integer::sum);
    }

    /**
     * Records that a value was approximated.
     *
     * @param field the field, written with {@code []} for array elements
     * @param message what was approximated; the same wording for every occurrence
     */
    public void warn(String field, String message) {
      warnings.merge(
          field + '\u0000' + message,
          new NormalisationWarning(field, message, 1),
          (a, b) -> new NormalisationWarning(field, message, a.occurrences() + 1));
    }

    /**
     * Finishes the report.
     *
     * @return the immutable report
     */
    public NormalisationReport build() {
      List<DefaultedField> fields = new ArrayList<>();
      defaults.forEach((field, count) -> fields.add(new DefaultedField(field, count)));
      return new NormalisationReport(fields, new ArrayList<>(warnings.values()));
    }
  }
}
