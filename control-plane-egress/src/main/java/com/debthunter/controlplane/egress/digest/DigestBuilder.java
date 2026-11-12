package com.debthunter.controlplane.egress.digest;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.store.lifecycle.SequencedTransition;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageLookup;
import com.debthunter.controlplane.store.suppressions.Suppression;
import com.debthunter.controlplane.store.suppressions.SuppressionRepository;
import com.debthunter.domain.Severity;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Works out what one squad should be told about one period. It looks only at the repositories it is
 * given, which the caller takes from the registry's owner, so a digest cannot contain another
 * squad's findings.
 */
public final class DigestBuilder {

  private static final int PAGE = 500;

  private final TransitionLogRepository log;
  private final PseudonymisedFindingStore findings;
  private final SuppressionRepository suppressions;
  private final StageLookup stages;
  private final OpenFindingCounts open;
  private final Function<String, String> squadLink;
  private final DigestSettings settings;
  private final Clock clock;

  /**
   * Creates the builder.
   *
   * @param log the transition log
   * @param findings the finding store, for the details of a listed finding
   * @param suppressions the suppression register
   * @param stages the rollout stages
   * @param open counts of open findings
   * @param squadLink the address of a squad's view, or null for none, given the squad
   * @param settings limits and the look-ahead
   * @param clock the source of "now"
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public DigestBuilder(
      TransitionLogRepository log,
      PseudonymisedFindingStore findings,
      SuppressionRepository suppressions,
      StageLookup stages,
      OpenFindingCounts open,
      Function<String, String> squadLink,
      DigestSettings settings,
      Clock clock) {
    this.log = Objects.requireNonNull(log, "log");
    this.findings = Objects.requireNonNull(findings, "findings");
    this.suppressions = Objects.requireNonNull(suppressions, "suppressions");
    this.stages = Objects.requireNonNull(stages, "stages");
    this.open = Objects.requireNonNull(open, "open");
    this.squadLink = Objects.requireNonNull(squadLink, "squadLink");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Builds a digest.
   *
   * @param squad the owning team
   * @param repositories the repositories it owns; nothing else is read
   * @param periodStart the start of the period
   * @param periodEnd the end of the period
   * @param fromSeq the last log position before the period
   * @param toSeq the last log position in the period
   * @return the digest
   */
  public DigestMessage build(
      String squad,
      Set<String> repositories,
      Instant periodStart,
      Instant periodEnd,
      long fromSeq,
      long toSeq) {
    Map<Severity, Integer> newBy = new TreeMap<>();
    int resolved = 0;
    int regressed = 0;
    Map<String, FindingTransitionEvent> listed = new LinkedHashMap<>();
    for (FindingTransitionEvent e : window(fromSeq, toSeq)) {
      if (!repositories.contains(e.repository())) {
        continue;
      }
      if (e.toState() == FindingState.RESOLVED) {
        resolved++;
      } else if (e.toState() == FindingState.REGRESSED) {
        regressed++;
        listed.put(key(e), e);
      } else if (e.toState() == FindingState.NEW) {
        listed.put(key(e), e);
      }
    }

    Map<String, Map<String, PseudonymisedFinding>> byRun = new HashMap<>();
    List<DigestMessage.Notable> candidates = new ArrayList<>();
    Set<String> files = new HashSet<>();
    for (FindingTransitionEvent e : listed.values()) {
      PseudonymisedFinding f =
          byRun.computeIfAbsent(e.runId(), id -> index(id)).get(e.fingerprint());
      if (f == null) {
        continue;
      }
      files.add(e.repository() + '\u0000' + f.path());
      boolean regression = e.toState() == FindingState.REGRESSED;
      if (!regression) {
        newBy.merge(f.severity(), 1, Integer::sum);
      }
      candidates.add(
          new DigestMessage.Notable(
              f.severity().name(),
              text(f.ruleId()),
              repositoryPath(e.repository(), f.path()),
              text(f.message()),
              regression));
    }
    candidates.sort(
        Comparator.comparing((DigestMessage.Notable n) -> !n.regression())
            .thenComparing(n -> Severity.valueOf(n.severity()).ordinal())
            .thenComparing(DigestMessage.Notable::path));
    List<DigestMessage.Notable> notable =
        candidates.subList(0, Math.min(settings.maxNotable(), candidates.size()));

    Instant now = clock.instant();
    List<DigestMessage.Expiring> expiring = new ArrayList<>();
    for (Suppression s : suppressions.findExpiring(now, now.plus(settings.expiryHorizon()))) {
      if (repositories.contains(s.repository())) {
        expiring.add(
            new DigestMessage.Expiring(
                text(s.repository()), s.expiresAt().toString(), text(s.reason())));
      }
    }

    Map<Severity, Integer> openNow = new TreeMap<>();
    List<String> sorted = repositories.stream().sorted().toList();
    for (String repository : sorted) {
      open.openBy(repository).forEach((sev, n) -> openNow.merge(sev, n, Integer::sum));
    }
    Map<String, RolloutStage> current = stages.currentStages();
    List<DigestMessage.StageLine> lines = new ArrayList<>();
    for (String repository : sorted.subList(0, Math.min(settings.maxStages(), sorted.size()))) {
      RolloutStage stage = current.get(repository);
      lines.add(
          new DigestMessage.StageLine(
              text(repository), stage == null ? "not in rollout" : stage.name()));
    }

    return new DigestMessage(
        squad,
        periodStart.toString(),
        periodEnd.toString(),
        named(newBy),
        resolved,
        regressed,
        expiring,
        named(openNow),
        lines,
        sorted.size() - lines.size(),
        new ArrayList<>(notable),
        candidates.size() - notable.size(),
        files.size(),
        squadLink.apply(squad));
  }

  private List<FindingTransitionEvent> window(long fromSeq, long toSeq) {
    List<FindingTransitionEvent> events = new ArrayList<>();
    long position = fromSeq;
    while (position < toSeq) {
      List<SequencedTransition> page = log.after(position, PAGE);
      if (page.isEmpty()) {
        break;
      }
      for (SequencedTransition t : page) {
        if (t.seq() > toSeq) {
          return events;
        }
        events.add(t.event());
        position = t.seq();
      }
    }
    return events;
  }

  private Map<String, PseudonymisedFinding> index(String runId) {
    Map<String, PseudonymisedFinding> byFingerprint = new HashMap<>();
    for (PseudonymisedFinding f : findings.findingsForRun(runId)) {
      byFingerprint.putIfAbsent(f.fingerprint(), f);
    }
    return byFingerprint;
  }

  private static String key(FindingTransitionEvent e) {
    return e.repository() + '\u0000' + e.projectSlice() + '\u0000' + e.fingerprint();
  }

  private String repositoryPath(String repository, String path) {
    return text(repository + ": " + path);
  }

  private static Map<String, Integer> named(Map<Severity, Integer> counts) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (Severity s : Severity.values()) {
      if (counts.getOrDefault(s, 0) > 0) {
        out.put(s.name(), counts.get(s));
      }
    }
    return out;
  }

  private String text(String value) {
    return DigestText.clean(value, settings.maxText());
  }
}
