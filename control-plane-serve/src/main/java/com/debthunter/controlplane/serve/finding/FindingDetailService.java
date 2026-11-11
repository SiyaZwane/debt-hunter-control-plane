package com.debthunter.controlplane.serve.finding;

import com.debthunter.controlplane.ai.explain.ExplainRequest;
import com.debthunter.controlplane.ai.explain.ExplanationService;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunRepository;
import java.util.Objects;
import java.util.Optional;

/**
 * Shows a finding with its explanation. The explanation is an extra: the explanation service never
 * throws, so whatever goes wrong producing one, the finding itself is still returned.
 *
 * <p>The finding must belong to the repository asked about; a run or finding from elsewhere is
 * simply not found. Whether the caller may see that repository is decided by the layer above.
 */
public final class FindingDetailService {

  private final RunRepository runs;
  private final PseudonymisedFindingStore findings;
  private final ExplanationService explanations;

  /**
   * Creates the service.
   *
   * @param runs the run store
   * @param findings the finding store
   * @param explanations produces explanations
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public FindingDetailService(
      RunRepository runs, PseudonymisedFindingStore findings, ExplanationService explanations) {
    this.runs = Objects.requireNonNull(runs, "runs");
    this.findings = Objects.requireNonNull(findings, "findings");
    this.explanations = Objects.requireNonNull(explanations, "explanations");
  }

  /**
   * A finding and its explanation.
   *
   * @param repository the repository
   * @param runId the run
   * @param findingId the finding
   * @return the detail, or empty if the run is not that repository's or has no such finding
   */
  public Optional<FindingDetail> detail(String repository, String runId, String findingId) {
    Optional<RunRecord> run = runs.find(runId);
    if (run.isEmpty() || !run.get().repository().equals(repository)) {
      return Optional.empty();
    }
    Optional<PseudonymisedFinding> found =
        findings.findingsForRun(runId).stream().filter(f -> f.id().equals(findingId)).findFirst();
    if (found.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new FindingDetail(
            repository,
            runId,
            FindingView.of(found.get()),
            explanations.explain(new ExplainRequest(repository, found.get()))));
  }
}
