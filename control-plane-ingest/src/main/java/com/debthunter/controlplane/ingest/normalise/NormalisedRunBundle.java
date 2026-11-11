package com.debthunter.controlplane.ingest.normalise;

import com.debthunter.domain.ScanResult;
import java.util.Objects;

/**
 * A run bundle in the control plane's own representation, independent of the schema version it
 * arrived in. Everything downstream of normalisation works with this and never sees a schema
 * version.
 *
 * <p>The content is the shared domain model ({@link ScanResult}), so the control plane and the data
 * plane cannot drift apart on what a finding is.
 *
 * <p><strong>This still carries raw contributor identity</strong> (finding evidence can name
 * authors). It must pass through the pseudonymiser before it reaches any store.
 *
 * @param scan the run, its findings, metrics, and policy outcome
 * @param provenance where it came from
 * @param report what normalisation defaulted or approximated
 */
public record NormalisedRunBundle(
    ScanResult scan, BundleProvenance provenance, NormalisationReport report) {

  /** Validates the components. */
  public NormalisedRunBundle {
    Objects.requireNonNull(scan, "scan");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(report, "report");
  }
}
