package com.debthunter.controlplane.analytics.projection;

import com.debthunter.controlplane.analytics.readmodel.HotspotPoint;
import com.debthunter.controlplane.analytics.readmodel.OpenFinding;
import com.debthunter.controlplane.analytics.readmodel.ProjectionBatch;
import com.debthunter.controlplane.analytics.readmodel.ReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.ResolutionRecord;
import com.debthunter.controlplane.analytics.readmodel.RuleDelta;
import com.debthunter.controlplane.analytics.readmodel.RunSnapshot;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.store.lifecycle.SequencedTransition;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunRepository;
import com.debthunter.controlplane.store.runs.SequencedRun;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follows the system of record and keeps the analytics read models up to date.
 *
 * <p>It <strong>only reads</strong> from the transition log, the run store and the finding store,
 * and only ever writes to the read models; an architecture test forbids it from calling any write
 * method of those. Nothing on the ingest path calls it, so a slow, failing or stopped projector
 * changes nothing about ingest: the read models just go stale.
 *
 * <p>Each step reads a batch from a source, derives its effect, and commits that effect together
 * with the new checkpoint in one transaction. A crash therefore leaves the batch either fully
 * applied or not at all, and a restart resumes exactly where it stopped without double-counting.
 */
public final class MetricProjector {

  static final String RUNS = "runs";
  static final String TRANSITIONS = "transitions";

  private static final Logger log = LoggerFactory.getLogger(MetricProjector.class);

  private final TransitionLogRepository transitions;
  private final RunRepository runs;
  private final PseudonymisedFindingStore findings;
  private final ReadModelRepository readModel;
  private final Clock clock;
  private final int batchSize;

  /**
   * Creates a projector.
   *
   * @param transitions the transition log to follow (read-only use)
   * @param runs the run store to follow (read-only use)
   * @param findings the finding store to read run contents from (read-only use)
   * @param readModel where the aggregates are written
   * @param clock the source of the completion time that staleness is judged against
   * @param batchSize how many items to read and commit at a time
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public MetricProjector(
      TransitionLogRepository transitions,
      RunRepository runs,
      PseudonymisedFindingStore findings,
      ReadModelRepository readModel,
      Clock clock,
      int batchSize) {
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.findings = Objects.requireNonNull(findings, "findings");
    this.readModel = Objects.requireNonNull(readModel, "readModel");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    this.batchSize = batchSize;
  }

  /**
   * Catches the read models up with the system of record.
   *
   * @return how many runs and transitions were projected
   */
  public int project() {
    int projected = projectRuns() + projectTransitions();
    // A pass that found nothing is still a completed pass: it proves the read models are current.
    readModel.commit(ProjectionBatch.empty(), clock.instant());
    return projected;
  }

  // ---- runs -----------------------------------------------------------------------------------

  private int projectRuns() {
    int total = 0;
    long position = readModel.checkpoint(RUNS);
    while (true) {
      List<SequencedRun> page = runs.activeAfter(position, batchSize);
      if (page.isEmpty()) {
        return total;
      }
      List<RunSnapshot> snapshots = new ArrayList<>();
      List<HotspotPoint> hotspots = new ArrayList<>();
      for (SequencedRun sequenced : page) {
        RunRecord run = sequenced.run();
        List<PseudonymisedFinding> contents = findings.findingsForRun(run.runId());
        snapshots.add(snapshot(run, contents));
        for (PseudonymisedFinding f : contents) {
          Double frequency = number(f.evidence().get("changeFrequency"));
          if (f.category() == Category.HOTSPOT && frequency != null) {
            hotspots.add(
                new HotspotPoint(
                    run.repository(),
                    run.projectSlice(),
                    run.commit(),
                    f.path(),
                    run.committedAt(),
                    frequency));
          }
        }
      }
      position = page.get(page.size() - 1).seq();
      readModel.commit(
          new ProjectionBatch(
              Map.of(RUNS, position),
              List.of(),
              snapshots,
              hotspots,
              List.of(),
              List.of(),
              List.of()),
          clock.instant());
      verifyAdvanced(RUNS, position);
      total += page.size();
    }
  }

  private static RunSnapshot snapshot(RunRecord run, List<PseudonymisedFinding> contents) {
    Map<Severity, Long> bySeverity =
        contents.stream()
            .collect(Collectors.groupingBy(PseudonymisedFinding::severity, Collectors.counting()));
    int architecture =
        (int) contents.stream().filter(f -> f.category() == Category.ARCHITECTURE).count();
    double complexity =
        contents.stream()
            .map(f -> number(f.evidence().get("complexityDelta")))
            .filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .sum();
    return new RunSnapshot(
        run.repository(),
        run.projectSlice(),
        run.commit(),
        run.runId(),
        run.committedAt(),
        bySeverity.getOrDefault(Severity.CRITICAL, 0L).intValue(),
        bySeverity.getOrDefault(Severity.HIGH, 0L).intValue(),
        bySeverity.getOrDefault(Severity.MEDIUM, 0L).intValue(),
        bySeverity.getOrDefault(Severity.LOW, 0L).intValue(),
        bySeverity.getOrDefault(Severity.INFO, 0L).intValue(),
        architecture,
        complexity,
        run.policyStatus());
  }

  // ---- transitions ----------------------------------------------------------------------------

  private int projectTransitions() {
    int total = 0;
    long position = readModel.checkpoint(TRANSITIONS);
    while (true) {
      List<SequencedTransition> page = transitions.after(position, batchSize);
      if (page.isEmpty()) {
        return total;
      }
      position = page.get(page.size() - 1).seq();
      readModel.commit(transitionBatch(page), clock.instant());
      verifyAdvanced(TRANSITIONS, position);
      total += page.size();
    }
  }

  /**
   * Checks that a commit really moved the checkpoint. If it did not, the same batch would be
   * applied again next pass and every count in it doubled, so this fails loudly instead.
   */
  private void verifyAdvanced(String name, long expected) {
    long actual = readModel.checkpoint(name);
    if (actual != expected) {
      throw new IllegalStateException(
          "The "
              + name
              + " checkpoint did not advance (expected "
              + expected
              + ", found "
              + actual
              + "); refusing to continue, because the batch would be applied twice");
    }
  }

  private ProjectionBatch transitionBatch(List<SequencedTransition> page) {
    Map<String, Map<String, PseudonymisedFinding>> runContents = new HashMap<>();
    Function<String, Map<String, PseudonymisedFinding>> contentsOf =
        runId ->
            runContents.computeIfAbsent(
                runId,
                id ->
                    findings.findingsForRun(id).stream()
                        .collect(
                            Collectors.toMap(
                                PseudonymisedFinding::fingerprint, f -> f, (a, b) -> a)));

    // What is open at this point in the batch, layered over what the read model already holds.
    Map<String, Optional<OpenFinding>> overlay = new LinkedHashMap<>();
    Map<String, OpenFinding> closed = new LinkedHashMap<>();
    List<ResolutionRecord> resolutions = new ArrayList<>();
    Map<String, int[]> deltas = new LinkedHashMap<>();
    Map<String, RuleDelta> deltaKeys = new LinkedHashMap<>();

    for (SequencedTransition sequenced : page) {
      FindingTransitionEvent e = sequenced.event();
      String openKey = e.repository() + '\u0000' + e.projectSlice() + '\u0000' + e.fingerprint();
      FindingState to = e.toState();

      if (to == FindingState.NEW || to == FindingState.REGRESSED) {
        PseudonymisedFinding f = contentsOf.apply(e.runId()).get(e.fingerprint());
        OpenFinding opened =
            new OpenFinding(
                e.repository(),
                e.projectSlice(),
                e.fingerprint(),
                f == null ? UNKNOWN : f.ruleId(),
                f == null ? UNKNOWN : f.severity().name(),
                f == null ? UNKNOWN : f.category().name(),
                e.timestamp());
        overlay.put(openKey, Optional.of(opened));
        count(deltas, deltaKeys, e, opened.ruleId(), to == FindingState.NEW ? 0 : 2);
      } else if (to == FindingState.RESOLVED) {
        Optional<OpenFinding> open =
            overlay.containsKey(openKey)
                ? overlay.get(openKey)
                : readModel.openFinding(e.repository(), e.projectSlice(), e.fingerprint());
        if (open.isPresent()) {
          OpenFinding o = open.get();
          long seconds = Math.max(0, Duration.between(o.since(), e.timestamp()).getSeconds());
          resolutions.add(
              new ResolutionRecord(
                  e.repository(),
                  e.projectSlice(),
                  e.fingerprint(),
                  o.ruleId(),
                  o.severity(),
                  o.category(),
                  e.timestamp(),
                  seconds));
          closed.put(openKey, o);
          count(deltas, deltaKeys, e, o.ruleId(), 1);
        } else {
          // Resolved with no record of it opening, so there is no duration to measure. Still
          // counted.
          log.warn("resolution with no recorded start fingerprint={}", e.fingerprint());
          count(deltas, deltaKeys, e, UNKNOWN, 1);
        }
        overlay.put(openKey, Optional.empty());
      }
    }

    List<OpenFinding> stillOpen = overlay.values().stream().flatMap(Optional::stream).toList();
    List<RuleDelta> ruleDeltas = new ArrayList<>();
    deltas.forEach(
        (key, c) -> {
          RuleDelta base = deltaKeys.get(key);
          ruleDeltas.add(
              new RuleDelta(
                  base.repository(),
                  base.projectSlice(),
                  base.ruleId(),
                  base.day(),
                  c[0],
                  c[1],
                  c[2]));
        });
    return new ProjectionBatch(
        Map.of(TRANSITIONS, page.get(page.size() - 1).seq()),
        ruleDeltas,
        List.of(),
        List.of(),
        List.copyOf(closed.values()),
        stillOpen,
        resolutions);
  }

  private static final String UNKNOWN = "unknown";

  /** Adds one to a rule's daily counter: kind 0 created, 1 resolved, 2 regressed. */
  private static void count(
      Map<String, int[]> deltas,
      Map<String, RuleDelta> keys,
      FindingTransitionEvent e,
      String ruleId,
      int kind) {
    LocalDate day = e.timestamp().atZone(ZoneOffset.UTC).toLocalDate();
    String key = e.repository() + '\u0000' + e.projectSlice() + '\u0000' + ruleId + '\u0000' + day;
    keys.computeIfAbsent(
        key, k -> new RuleDelta(e.repository(), e.projectSlice(), ruleId, day, 0, 0, 0));
    deltas.computeIfAbsent(key, k -> new int[3])[kind]++;
  }

  private static Double number(Object value) {
    return value instanceof Number n ? n.doubleValue() : null;
  }
}
