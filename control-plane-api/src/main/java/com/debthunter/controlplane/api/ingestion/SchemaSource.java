package com.debthunter.controlplane.api.ingestion;

import java.util.Optional;

/** Supplies the JSON Schema text for a run-bundle schema major version. */
public interface SchemaSource {

  /**
   * The schema for a major version.
   *
   * @param major the schema major version
   * @return the schema's JSON text, or empty if no schema exists for that version
   */
  Optional<String> schemaFor(int major);
}
