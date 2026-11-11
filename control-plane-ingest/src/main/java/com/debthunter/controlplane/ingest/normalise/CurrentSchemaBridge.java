package com.debthunter.controlplane.ingest.normalise;

import com.debthunter.domain.ScanResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Bridge for schema major 1, the current version. Every field of the current schema is required, so
 * a bundle missing one is malformed rather than defaulted.
 */
public final class CurrentSchemaBridge implements SchemaBridge {

  @Override
  public int major() {
    return 1;
  }

  @Override
  public ScanResult bridge(JsonNode bundle, NormalisationReport.Builder report) {
    return new BundleMapper(false, report).map(bundle);
  }
}
