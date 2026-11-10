package com.debthunter.controlplane.api.ingestion;

import java.util.List;

/** The outcome of validating a bundle against the schema versions the control plane accepts. */
public sealed interface SchemaValidationResult {

  /**
   * The bundle conforms to a supported schema version.
   *
   * @param major the schema major version it was validated against
   * @param schemaVersion the version the bundle declared
   */
  record Valid(int major, String schemaVersion) implements SchemaValidationResult {}

  /**
   * The bundle declares a schema version outside the supported window.
   *
   * @param declared the version the bundle declared
   * @param reason why it is unsupported — older than the oldest, or newer than the newest
   * @param supportedMajors the major versions that are accepted, newest first
   */
  record UnsupportedVersion(String declared, String reason, List<Integer> supportedMajors)
      implements SchemaValidationResult {

    /** Takes a defensive copy of the supported versions. */
    public UnsupportedVersion {
      supportedMajors = List.copyOf(supportedMajors);
    }
  }

  /**
   * The bundle does not conform to its declared schema version.
   *
   * @param violations what is wrong, in a stable order, capped at a reportable number
   * @param totalViolations how many violations there were before the cap
   */
  record Invalid(List<SchemaViolation> violations, int totalViolations)
      implements SchemaValidationResult {

    /** Takes a defensive copy of the violations. */
    public Invalid {
      violations = List.copyOf(violations);
    }
  }
}
