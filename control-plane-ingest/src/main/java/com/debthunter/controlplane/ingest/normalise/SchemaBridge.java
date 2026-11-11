package com.debthunter.controlplane.ingest.normalise;

import com.debthunter.domain.ScanResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Maps the bundles of one schema major version onto the internal representation.
 *
 * <p>Supporting a new schema version means adding a bridge and registering it with the {@link
 * RunNormaliser}; nothing already there changes.
 */
public interface SchemaBridge {

  /**
   * The schema major version this bridge reads.
   *
   * @return the major version
   */
  int major();

  /**
   * Maps a bundle.
   *
   * @param bundle the parsed bundle, known to declare this bridge's major version
   * @param report where to record defaults and approximations
   * @return the bundle's content
   * @throws UnnormalisableBundleException if the bundle cannot be mapped at all
   */
  ScanResult bridge(JsonNode bundle, NormalisationReport.Builder report);
}
