package com.debthunter.controlplane.ingest.pseudonymise;

import com.debthunter.controlplane.ingest.normalise.BundleProvenance;
import com.debthunter.controlplane.ingest.normalise.NormalisationReport;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.domain.AnalysisRun;
import com.debthunter.domain.DebtMetric;
import com.debthunter.domain.PolicyResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A run bundle that is safe to persist: the same content as a normalised bundle, but with every
 * finding's contributors reduced to pseudonyms.
 *
 * @param run the run
 * @param findings the findings, pseudonymised
 * @param metrics the run's metrics
 * @param policy the policy outcome
 * @param provenance where the bundle came from
 * @param normalisation what normalisation defaulted or approximated
 * @param pseudonymisation what pseudonymisation left out
 */
public record PseudonymisedRunBundle(
    AnalysisRun run,
    List<PseudonymisedFinding> findings,
    Map<String, DebtMetric> metrics,
    PolicyResult policy,
    BundleProvenance provenance,
    NormalisationReport normalisation,
    PseudonymisationReport pseudonymisation) {

  /** Validates and defensively copies the components. */
  public PseudonymisedRunBundle {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(normalisation, "normalisation");
    Objects.requireNonNull(pseudonymisation, "pseudonymisation");
    findings = findings == null ? List.of() : List.copyOf(findings);
    metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
  }
}
