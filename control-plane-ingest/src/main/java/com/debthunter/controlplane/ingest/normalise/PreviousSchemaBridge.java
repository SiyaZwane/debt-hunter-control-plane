package com.debthunter.controlplane.ingest.normalise;

import com.debthunter.domain.ScanResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Bridge for schema major 0, the N-1 version. Fields the current schema added are defaulted from
 * {@link NormalisationDefaults} and each one is recorded in the report; fields whose type was
 * loosened since are mapped to the closest current equivalent, with a warning.
 *
 * <p>Provisional: no real v0 has ever been emitted by the data plane (see the ingestion API's
 * schema notes), so the field list follows the provisional v0 schema.
 */
public final class PreviousSchemaBridge implements SchemaBridge {

  @Override
  public int major() {
    return 0;
  }

  @Override
  public ScanResult bridge(JsonNode bundle, NormalisationReport.Builder report) {
    return new BundleMapper(true, report).map(bundle);
  }
}
