package com.debthunter.controlplane.serve.evidence;

import com.debthunter.controlplane.serve.access.Access;
import com.debthunter.controlplane.serve.access.Caller;
import com.debthunter.controlplane.serve.access.InvalidRequestException;
import com.debthunter.controlplane.serve.access.NotFoundException;
import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.serve.access.ServiceUnavailableException;
import com.debthunter.controlplane.serve.finding.FindingView;
import com.debthunter.controlplane.store.evidence.EvidenceAccess;
import com.debthunter.controlplane.store.evidence.EvidenceAccessLog;
import com.debthunter.controlplane.store.evidence.EvidenceRepository;
import com.debthunter.controlplane.store.evidence.EvidenceSnapshot;
import com.debthunter.controlplane.store.evidence.EvidenceSummary;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.policy.PolicyBundleRecord;
import com.debthunter.controlplane.store.policy.PolicyCatalogueRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Given a run, returns its metadata, findings, retained evidence, the policy bundle it was assessed
 * against and the outcome it recorded, for an investigation.
 *
 * <p>It is the one place evidence leaves the control plane, so every request is recorded first
 * (who, which run, and the stated purpose) and nothing is released if the record cannot be made. A
 * caller sees only runs of repositories owned by the squads in their token, and a run that does not
 * exist and one they may not see look the same to them; the record keeps the difference.
 *
 * <p>Evidence is read from whichever tier holds it. Detail ages out of the hot tier into daily
 * summaries, so an old run returns its summaries, marked as such, not the detail that no longer
 * exists.
 */
public final class EvidenceRetrievalService {

  private static final Logger LOG = LoggerFactory.getLogger(EvidenceRetrievalService.class);

  /** The shortest purpose accepted. */
  public static final int MIN_PURPOSE = 10;

  /** The longest purpose kept. */
  public static final int MAX_PURPOSE = 500;

  private final RunRepository runs;
  private final PseudonymisedFindingStore findings;
  private final EvidenceRepository evidence;
  private final PolicyCatalogueRepository policies;
  private final RepositoryRegistry registry;
  private final EvidenceAccessLog log;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param runs the run store
   * @param findings the finding store
   * @param evidence the evidence tiers
   * @param policies the policy catalogue
   * @param registry which squad owns which repository
   * @param log where every request is recorded
   * @param clock the source of "now"
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public EvidenceRetrievalService(
      RunRepository runs,
      PseudonymisedFindingStore findings,
      EvidenceRepository evidence,
      PolicyCatalogueRepository policies,
      RepositoryRegistry registry,
      EvidenceAccessLog log,
      Clock clock) {
    this.runs = Objects.requireNonNull(runs, "runs");
    this.findings = Objects.requireNonNull(findings, "findings");
    this.evidence = Objects.requireNonNull(evidence, "evidence");
    this.policies = Objects.requireNonNull(policies, "policies");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.log = Objects.requireNonNull(log, "log");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Retrieves a run's evidence.
   *
   * @param caller who is asking
   * @param runId the run
   * @param purpose why, in the caller's words; at least {@value #MIN_PURPOSE} characters
   * @return everything held about the run
   * @throws com.debthunter.controlplane.serve.access.AccessDeniedException if the caller lacks the
   *     evidence role
   * @throws InvalidRequestException if the purpose is missing or too short
   * @throws NotFoundException if there is no such run, or the caller may not see it
   * @throws ServiceUnavailableException if the request could not be recorded, in which case nothing
   *     was released
   */
  public RunEvidence retrieve(Caller caller, String runId, String purpose) {
    Access.requireRole(caller, Roles.EVIDENCE);
    String reason = cleanPurpose(purpose);

    Optional<RunRecord> run;
    try {
      run = runs.find(runId);
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
    EvidenceAccess.Outcome outcome;
    if (run.isEmpty()) {
      outcome = EvidenceAccess.Outcome.NOT_FOUND;
    } else if (mayView(caller, run.get().repository())) {
      outcome = EvidenceAccess.Outcome.GRANTED;
    } else {
      outcome = EvidenceAccess.Outcome.DENIED;
    }
    try {
      log.record(
          new EvidenceAccess(
              clock.instant(),
              caller.subject(),
              runId,
              run.map(RunRecord::repository).orElse(null),
              reason,
              outcome));
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
    if (outcome != EvidenceAccess.Outcome.GRANTED) {
      throw new NotFoundException("There is no such run");
    }
    try {
      return assemble(run.get());
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
  }

  private boolean mayView(Caller caller, String repository) {
    return registry.find(repository).map(r -> caller.isIn(r.owner())).orElse(false);
  }

  private RunEvidence assemble(RunRecord run) {
    List<FindingView> views =
        findings.findingsForRun(run.runId()).stream().map(FindingView::of).toList();
    return new RunEvidence(
        run.runId(),
        new RunEvidence.RunMetadata(
            run.repository(),
            run.projectSlice(),
            run.commit(),
            run.toolVersion(),
            run.degraded(),
            run.scopeLimited(),
            run.engines(),
            run.committedAt(),
            run.receivedAt(),
            run.status().name()),
        views,
        evidenceOf(run),
        policyOf(run),
        clock.instant());
  }

  private RunEvidence.Evidence evidenceOf(RunRecord run) {
    List<EvidenceSnapshot> hot = evidence.hotForRun(run.runId());
    if (!hot.isEmpty()) {
      return new RunEvidence.Evidence(
          RunEvidence.Tier.HOT,
          hot.stream()
              .map(
                  s ->
                      new RunEvidence.Snapshot(
                          s.findingId(),
                          s.ruleId(),
                          s.severity(),
                          s.evidence(),
                          s.capturedAt(),
                          s.expiresAt()))
              .toList(),
          List.of());
    }
    // Evidence is stamped with the commit date, so that is the day its summary falls on.
    Instant when = run.committedAt() != null ? run.committedAt() : run.receivedAt();
    if (when == null) {
      return new RunEvidence.Evidence(RunEvidence.Tier.NONE, List.of(), List.of());
    }
    LocalDate day = when.atZone(ZoneOffset.UTC).toLocalDate();
    List<EvidenceSummary> summaries =
        evidence.warmSummaries(run.repository()).stream().filter(s -> s.day().equals(day)).toList();
    return new RunEvidence.Evidence(
        summaries.isEmpty() ? RunEvidence.Tier.NONE : RunEvidence.Tier.WARM, List.of(), summaries);
  }

  private RunEvidence.Policy policyOf(RunRecord run) {
    String version = run.policyBundleVersion();
    if (version == null) {
      return new RunEvidence.Policy(false, null, null, null, null, run.policyStatus());
    }
    Optional<PolicyBundleRecord> bundle = policies.find(version);
    return new RunEvidence.Policy(
        true,
        version,
        bundle.map(PolicyBundleRecord::digest).orElse(null),
        bundle.map(PolicyBundleRecord::ratifiedAt).orElse(null),
        bundle.map(PolicyBundleRecord::supersededBy).orElse(null),
        run.policyStatus());
  }

  private static String cleanPurpose(String purpose) {
    if (purpose == null) {
      throw new InvalidRequestException("A purpose is required");
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < purpose.length() && out.length() < MAX_PURPOSE; i++) {
      char c = purpose.charAt(i);
      out.append(Character.isISOControl(c) ? ' ' : c);
    }
    String cleaned = out.toString().strip();
    if (cleaned.length() < MIN_PURPOSE) {
      throw new InvalidRequestException(
          "A purpose of at least " + MIN_PURPOSE + " characters is required");
    }
    return cleaned;
  }

  private static ServiceUnavailableException unavailable(RuntimeException cause) {
    LOG.error("evidence retrieval could not complete", cause);
    return new ServiceUnavailableException("The evidence could not be retrieved now");
  }
}
