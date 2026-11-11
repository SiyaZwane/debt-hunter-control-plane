package com.debthunter.controlplane.governance.simulation;

import com.debthunter.controlplane.governance.policy.ObservedEstate;
import com.debthunter.controlplane.governance.policy.ObservedRepository;
import com.debthunter.controlplane.governance.policy.PolicyBundleValidator;
import com.debthunter.controlplane.governance.policy.ValidationReport;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunStatus;
import com.debthunter.domain.Finding;
import com.debthunter.domain.HistoryDepth;
import com.debthunter.domain.PolicyResult;
import com.debthunter.domain.PolicyViolation;
import com.debthunter.domain.SuppressionEntry;
import com.debthunter.engine.spi.AnalysisMode;
import com.debthunter.policy.PolicyBundle;
import com.debthunter.policy.PolicyEvaluator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replays a candidate policy against the evidence the control plane has already retained, and says
 * what it would do to each repository and to the estate — before anyone is held to it.
 *
 * <p><strong>It changes nothing.</strong> It is given a {@link RetainedEvidence}, which has only
 * read methods, and it keeps its working state in locals of one call. There is nothing for it to
 * write to and nowhere for it to remember anything.
 *
 * <p><strong>It judges with the data plane's own evaluator</strong>, the same {@link
 * PolicyEvaluator} a pipeline runs, over the findings exactly as stored (including whether each was
 * new), with suppressions as they stood at the run's commit date. A simulated verdict is therefore
 * the verdict the pipeline would have given.
 *
 * <p><strong>It never fills a gap with a pass.</strong> A repository with no usable evidence is
 * reported as {@link RepositoryImpact.NotSimulated}, with the reason, and counted in the share of
 * the estate that could not be simulated. Only complete runs are replayed: a degraded or
 * scope-limited run has fewer findings than the repository really has, so passing it proves
 * nothing.
 *
 * <p>What is replayed is the main-branch rule set against each repository's complete, applied runs.
 * Pull-request runs are scope-limited and are not replayed.
 */
public final class PolicyImpactSimulator {

  private static final Logger LOG = LoggerFactory.getLogger(PolicyImpactSimulator.class);

  /** A suppression found active is handed to the evaluator as one that has not expired. */
  private static final LocalDate NEVER_EXPIRES = LocalDate.of(9999, 12, 31);

  /** Runs fetched per repository is a multiple of those replayed, since not all are complete. */
  private static final int FETCH_MULTIPLE = 4;

  private static final int FETCH_CEILING = 2000;

  private static final String HISTORY_RULE = "minimum-history-depth";

  private final RetainedEvidence evidence;
  private final PolicyBundleSource bundles;
  private final PolicyBundleValidator validator;
  private final Clock clock;
  private final PolicyEvaluator evaluator = new PolicyEvaluator();

  /**
   * Creates a simulator.
   *
   * @param evidence the retained evidence to replay; read-only by construction
   * @param bundles where the content of bundles already in force comes from
   * @param validator checks the candidate before anything is read
   * @param clock the source of "now"; the lookback window is measured back from it
   */
  public PolicyImpactSimulator(
      RetainedEvidence evidence,
      PolicyBundleSource bundles,
      PolicyBundleValidator validator,
      Clock clock) {
    this.evidence = Objects.requireNonNull(evidence, "evidence");
    this.bundles = Objects.requireNonNull(bundles, "bundles");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Validates a candidate, including whether the estate as it stands could ever meet its
   * thresholds, without simulating it. For an author who wants feedback before asking for the full
   * replay.
   *
   * @param candidateYaml the candidate bundle's text
   * @param request the window to observe the estate over
   * @return the validation verdict
   */
  public ValidationReport validate(String candidateYaml, SimulationRequest request) {
    Objects.requireNonNull(request, "request");
    Working working = new Working(clock.instant(), request);
    return validator.validate(candidateYaml, working::observed);
  }

  /**
   * Validates the candidate and, if it has no errors, simulates it.
   *
   * <p>A candidate with errors is returned as {@link SimulationOutcome.Rejected} before the system
   * of record is read at all.
   *
   * @param candidateYaml the candidate bundle's text
   * @param request the window and bound for the replay
   * @return the rejection, or the impact report
   */
  public SimulationOutcome simulate(String candidateYaml, SimulationRequest request) {
    Objects.requireNonNull(request, "request");
    Instant asOf = clock.instant();
    Working working = new Working(asOf, request);

    ValidationReport validation = validator.validate(candidateYaml, working::observed);
    if (!validation.isValid()) {
      return new SimulationOutcome.Rejected(validation);
    }
    PolicyBundle candidate = validation.parsedBundle().orElseThrow();

    Map<String, Optional<PolicyBundle>> currentBundles = new HashMap<>();
    List<RepositoryImpact> impacts = new ArrayList<>();
    for (OnboardedRepository repository : working.estate()) {
      Optional<PolicyBundle> current =
          currentBundles.computeIfAbsent(
              repository.policyBundleVersion(), this::currentBundleOrEmpty);
      impacts.add(impactOf(repository, working.evidenceOf(repository), candidate, current));
    }

    EstateSummary summary = summarise(impacts);
    LOG.info(
        "Simulated policy {} over {} repositories ({} could not be simulated)",
        candidate.version(),
        summary.overall().repositories(),
        summary.overall().notSimulated());
    return new SimulationOutcome.Completed(
        new ImpactReport(
            candidate.version(), asOf, request.lookback(), validation, impacts, summary));
  }

  // ---- evidence for one simulation -------------------------------------------------------------

  /** The evidence read for one call, loaded the first time it is needed and never kept. */
  private final class Working {

    private final Instant asOf;
    private final SimulationRequest request;
    private List<OnboardedRepository> estate;
    private final Map<String, RepositoryEvidence> byRepository = new HashMap<>();

    Working(Instant asOf, SimulationRequest request) {
      this.asOf = asOf;
      this.request = request;
    }

    List<OnboardedRepository> estate() {
      if (estate == null) {
        estate =
            evidence.estate().stream()
                .sorted(Comparator.comparing(OnboardedRepository::repository))
                .toList();
      }
      return estate;
    }

    RepositoryEvidence evidenceOf(OnboardedRepository repository) {
      return byRepository.computeIfAbsent(repository.repository(), this::load);
    }

    ObservedEstate observed() {
      List<ObservedRepository> observed = new ArrayList<>();
      for (OnboardedRepository repository : estate()) {
        if (evidenceOf(repository) instanceof RepositoryEvidence.Loaded loaded
            && !loaded.complete().isEmpty()) {
          RunEvidence latest = loaded.complete().get(0);
          observed.add(
              new ObservedRepository(
                  repository.repository(), latest.findings(), latest.suppressions()));
        }
      }
      return new ObservedEstate(observed);
    }

    private RepositoryEvidence load(String repository) {
      try {
        return loadWindow(repository);
      } catch (RuntimeException e) {
        LOG.warn(
            "Evidence for {} could not be read; it is left out of the simulation", repository, e);
        return new RepositoryEvidence.Failed();
      }
    }

    private RepositoryEvidence.Loaded loadWindow(String repository) {
      Instant from = asOf.minus(request.lookback());
      int fetch = Math.min(request.maxRunsPerRepository() * FETCH_MULTIPLE, FETCH_CEILING);
      List<RunRecord> fetched = evidence.recentRuns(repository, fetch);

      List<RunRecord> inWindow =
          fetched.stream()
              .filter(r -> placement(r) != null)
              .filter(r -> !placement(r).isBefore(from) && !placement(r).isAfter(asOf))
              .sorted(
                  Comparator.comparing(PolicyImpactSimulator::placement)
                      .reversed()
                      .thenComparing(RunRecord::runId))
              .toList();

      List<RunRecord> complete =
          inWindow.stream().filter(r -> r.status() == RunStatus.ACTIVE && r.isComplete()).toList();

      boolean fetchWasCapped =
          fetched.size() >= fetch
              && fetched.stream()
                  .map(PolicyImpactSimulator::placement)
                  .filter(Objects::nonNull)
                  .anyMatch(at -> !at.isBefore(from));
      boolean truncated = complete.size() > request.maxRunsPerRepository() || fetchWasCapped;

      List<RunEvidence> runs = new ArrayList<>();
      for (RunRecord run : complete.stream().limit(request.maxRunsPerRepository()).toList()) {
        runs.add(runEvidence(run));
      }
      return new RepositoryEvidence.Loaded(inWindow.size(), runs, truncated);
    }

    private RunEvidence runEvidence(RunRecord run) {
      Instant at = placement(run);
      List<Finding> findings = new ArrayList<>();
      List<SuppressionEntry> suppressions = new ArrayList<>();
      for (PseudonymisedFinding stored : evidence.findingsOf(run.runId())) {
        Finding finding = toDomain(stored);
        findings.add(finding);
        // A finding that is not new never counts toward a threshold, so its suppression status
        // cannot matter; skipping the lookup keeps a large replay affordable.
        if (finding.isNew() && evidence.suppressedAt(run.repository(), finding.fingerprint(), at)) {
          suppressions.add(
              new SuppressionEntry(
                  finding.fingerprint(), "control-plane", "suppression in force", NEVER_EXPIRES));
        }
      }
      return new RunEvidence(run, findings, suppressions);
    }
  }

  private static Instant placement(RunRecord run) {
    return run.committedAt() != null ? run.committedAt() : run.receivedAt();
  }

  private static Finding toDomain(PseudonymisedFinding f) {
    return new Finding(
        f.id(),
        f.ruleId(),
        f.category(),
        f.severity(),
        f.confidence(),
        f.path(),
        f.startLine(),
        f.message(),
        f.evidence(),
        f.score(),
        f.isNew(),
        f.fingerprint());
  }

  private sealed interface RepositoryEvidence {
    /** The window was read: how many runs fell in it, and the complete ones newest first. */
    record Loaded(int runsInWindow, List<RunEvidence> complete, boolean truncated)
        implements RepositoryEvidence {}

    /** Reading the window failed. */
    record Failed() implements RepositoryEvidence {}
  }

  private record RunEvidence(
      RunRecord run, List<Finding> findings, List<SuppressionEntry> suppressions) {}

  // ---- replaying one repository ----------------------------------------------------------------

  private Optional<PolicyBundle> currentBundleOrEmpty(String version) {
    try {
      return bundles.find(version);
    } catch (RuntimeException e) {
      LOG.warn("The current policy {} could not be loaded; no comparison will be made", version, e);
      return Optional.empty();
    }
  }

  private RepositoryImpact impactOf(
      OnboardedRepository repository,
      RepositoryEvidence evidenceOfRepository,
      PolicyBundle candidate,
      Optional<PolicyBundle> current) {
    if (!(evidenceOfRepository instanceof RepositoryEvidence.Loaded loaded)) {
      return notSimulated(repository, NotSimulatedReason.EVIDENCE_UNAVAILABLE);
    }
    if (loaded.runsInWindow() == 0) {
      return notSimulated(repository, NotSimulatedReason.NO_RUNS_IN_WINDOW);
    }
    if (loaded.complete().isEmpty()) {
      return notSimulated(repository, NotSimulatedReason.NO_COMPLETE_RUN_IN_WINDOW);
    }

    List<Replay> underCandidate =
        loaded.complete().stream().map(run -> replay(candidate, run)).toList();
    Replay latestCandidate = underCandidate.get(0);
    if (!latestCandidate.determinable()) {
      return notSimulated(repository, NotSimulatedReason.HISTORY_DEPTH_UNKNOWN);
    }

    List<Replay> underCurrent =
        current
            .map(c -> loaded.complete().stream().map(run -> replay(c, run)).toList())
            .orElse(null);
    Replay latestCurrent =
        underCurrent == null || !underCurrent.get(0).determinable() ? null : underCurrent.get(0);

    RunEvidence latestRun = loaded.complete().get(0);
    Delta delta = deltaOf(latestCandidate, latestCurrent);
    List<BlockingFinding> newlyBlocking =
        latestCurrent == null
            ? List.of()
            : newlyBlocking(latestRun, latestCandidate, latestCurrent);

    int undeterminable = (int) underCandidate.stream().filter(r -> !r.determinable()).count();
    return new RepositoryImpact.Simulated(
        repository.repository(),
        repository.tier(),
        repository.rolloutStage(),
        repository.policyBundleVersion(),
        latestRun.run().runId(),
        placement(latestRun.run()),
        !latestCandidate.fails(),
        latestCandidate.violations(),
        delta,
        newlyBlocking,
        loaded.complete().size(),
        (int) underCandidate.stream().filter(Replay::fails).count(),
        underCurrent == null ? null : (int) underCurrent.stream().filter(Replay::fails).count(),
        undeterminable,
        loaded.truncated());
  }

  private static RepositoryImpact notSimulated(
      OnboardedRepository repository, NotSimulatedReason reason) {
    return new RepositoryImpact.NotSimulated(
        repository.repository(), repository.tier(), repository.rolloutStage(), reason);
  }

  private static Delta deltaOf(Replay candidate, Replay current) {
    if (current == null) {
      return Delta.UNKNOWN;
    }
    if (candidate.fails() && !current.fails()) {
      return Delta.NEWLY_FAILING;
    }
    if (!candidate.fails() && current.fails()) {
      return Delta.NEWLY_PASSING;
    }
    return candidate.fails() ? Delta.UNCHANGED_FAILING : Delta.UNCHANGED_PASSING;
  }

  private static List<BlockingFinding> newlyBlocking(
      RunEvidence run, Replay candidate, Replay current) {
    Set<String> alreadyBlocked = new HashSet<>();
    current.violations().forEach(v -> alreadyBlocked.addAll(v.findingIds()));

    Map<String, List<String>> rulesByFinding = new LinkedHashMap<>();
    for (PolicyViolation violation : candidate.violations()) {
      for (String id : violation.findingIds()) {
        if (!alreadyBlocked.contains(id)) {
          rulesByFinding.computeIfAbsent(id, k -> new ArrayList<>()).add(violation.rule());
        }
      }
    }

    Map<String, Finding> byId = new HashMap<>();
    run.findings().forEach(f -> byId.put(f.id(), f));
    List<BlockingFinding> result = new ArrayList<>();
    rulesByFinding.forEach(
        (id, rules) -> {
          Finding f = byId.get(id);
          if (f != null) {
            result.add(
                new BlockingFinding(
                    f.id(), f.ruleId(), f.fingerprint(), f.severity(), f.path(), rules));
          }
        });
    result.sort(
        Comparator.comparing(BlockingFinding::severity)
            .thenComparing(BlockingFinding::path)
            .thenComparing(BlockingFinding::findingId));
    return result;
  }

  /** A run's outcome under one policy. */
  private record Replay(boolean determinable, List<PolicyViolation> violations) {
    boolean fails() {
      return !violations.isEmpty();
    }
  }

  private Replay replay(PolicyBundle bundle, RunEvidence run) {
    PolicyResult result =
        evaluator.evaluate(run.findings(), bundle, AnalysisMode.FULL, null, run.suppressions());
    List<PolicyViolation> violations = new ArrayList<>(result.reasons());

    HistoryDepth required = bundle.minimumHistoryDepth();
    HistoryDepth actual = depthOf(run.run());
    if (required != null && actual != null && actual.ordinal() > required.ordinal()) {
      violations.add(
          new PolicyViolation(
              HISTORY_RULE,
              "history depth " + required + " or deeper",
              actual.toString(),
              List.of()));
    }
    // If the policy asks for a history depth and the run never said what it had, a run that broke
    // no threshold might still have failed on depth. Say so rather than guess.
    boolean determinable = !(required != null && actual == null && violations.isEmpty());
    return new Replay(determinable, violations);
  }

  private static HistoryDepth depthOf(RunRecord run) {
    if (run.historyDepth() == null) {
      return null;
    }
    try {
      return HistoryDepth.valueOf(run.historyDepth());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  // ---- the estate roll-up ----------------------------------------------------------------------

  private static EstateSummary summarise(List<RepositoryImpact> impacts) {
    Set<String> tiers = new TreeSet<>();
    Set<String> stages = new TreeSet<>();
    Set<String> tierAndStage = new TreeSet<>();
    for (RepositoryImpact impact : impacts) {
      tiers.add(impact.tier());
      stages.add(impact.stage());
      tierAndStage.add(impact.tier() + "\u0000" + impact.stage());
    }

    List<GroupSummary> byTier = new ArrayList<>();
    for (String tier : tiers) {
      byTier.add(group(tier, null, impacts, i -> i.tier().equals(tier)));
    }
    List<GroupSummary> byStage = new ArrayList<>();
    for (String stage : stages) {
      byStage.add(group(null, stage, impacts, i -> i.stage().equals(stage)));
    }
    List<GroupSummary> byBoth = new ArrayList<>();
    for (String key : tierAndStage) {
      String[] parts = key.split("\u0000", 2);
      byBoth.add(
          group(
              parts[0],
              parts[1],
              impacts,
              i -> i.tier().equals(parts[0]) && i.stage().equals(parts[1])));
    }
    return new EstateSummary(group(null, null, impacts, i -> true), byTier, byStage, byBoth);
  }

  private static GroupSummary group(
      String tier, String stage, List<RepositoryImpact> impacts, Predicate<RepositoryImpact> in) {
    int repositories = 0;
    int simulated = 0;
    int passing = 0;
    int failing = 0;
    int newlyFailing = 0;
    int newlyPassing = 0;
    int unknown = 0;
    for (RepositoryImpact impact : impacts) {
      if (!in.test(impact)) {
        continue;
      }
      repositories++;
      if (impact instanceof RepositoryImpact.Simulated s) {
        simulated++;
        if (s.candidatePasses()) {
          passing++;
        } else {
          failing++;
        }
        switch (s.delta()) {
          case NEWLY_FAILING -> newlyFailing++;
          case NEWLY_PASSING -> newlyPassing++;
          case UNKNOWN -> unknown++;
          default -> {
            // unchanged: counted in passing/failing only
          }
        }
      }
    }
    return new GroupSummary(
        tier,
        stage,
        repositories,
        simulated,
        repositories - simulated,
        passing,
        failing,
        newlyFailing,
        newlyPassing,
        unknown);
  }
}
