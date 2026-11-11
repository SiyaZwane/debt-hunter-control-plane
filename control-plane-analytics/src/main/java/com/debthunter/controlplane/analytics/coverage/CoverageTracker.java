package com.debthunter.controlplane.analytics.coverage;

import com.debthunter.controlplane.analytics.coverage.estate.EstateUnavailableException;
import com.debthunter.controlplane.analytics.coverage.estate.SourceControlEstateProvider;
import com.debthunter.controlplane.analytics.readmodel.Staleness;
import com.debthunter.controlplane.analytics.readmodel.Trended;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageLookup;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reconciles the repository registry against the source-control estate and says, for every
 * repository in either, whether the control plane can be trusted about it.
 *
 * <p><strong>Silence is a finding.</strong> A repository that has stopped reporting looks exactly
 * like a clean one, and one nobody onboarded has no findings simply because nobody is looking. Both
 * are reported as what they are, never as healthy.
 *
 * <p>Each repository gets exactly one {@link CoverageState}. When several conditions apply the most
 * serious wins, in this order: {@code SILENT}, then {@code DEGRADED_REPORTING}, then {@code
 * VERSION_DRIFT}, then {@code REPORTING}; the {@code reasons} list still names all of them.
 *
 * <p>Repositories are matched case-insensitively, since GitHub and Azure DevOps names are.
 */
public final class CoverageTracker {

  private final RepositoryRegistryService registry;
  private final RunRepository runs;
  private final SourceControlEstateProvider estate;
  private final CoverageRepository coverage;
  private final CoverageSettings settings;
  private final Clock clock;
  private final Staleness staleness;
  private final StageLookup stages;

  /**
   * Creates the tracker.
   *
   * @param registry the repositories that are onboarded
   * @param runs the run store (read-only use)
   * @param estate lists the source-control estate, or {@code null} if none is configured
   * @param coverage where the latest report is kept
   * @param settings what silent, degraded and drifted mean
   * @param clock the source of "now": silence is a wall-clock notion
   * @param staleness decides whether a stored report is too old to trust
   */
  public CoverageTracker(
      RepositoryRegistryService registry,
      RunRepository runs,
      SourceControlEstateProvider estate,
      CoverageRepository coverage,
      CoverageSettings settings,
      Clock clock,
      Staleness staleness) {
    this(registry, runs, estate, coverage, settings, clock, staleness, Map::of);
  }

  /**
   * Creates the tracker, showing each repository's rollout stage in its report.
   *
   * @param registry the repositories that are onboarded
   * @param runs the run store (read-only use)
   * @param estate lists the source-control estate, or {@code null} if none is configured
   * @param coverage where the latest report is kept
   * @param settings what silent, degraded and drifted mean
   * @param clock the source of "now": silence is a wall-clock notion
   * @param staleness decides whether a stored report is too old to trust
   * @param stages where repositories are in the rollout
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public CoverageTracker(
      RepositoryRegistryService registry,
      RunRepository runs,
      SourceControlEstateProvider estate,
      CoverageRepository coverage,
      CoverageSettings settings,
      Clock clock,
      Staleness staleness,
      StageLookup stages) {
    this.stages = Objects.requireNonNull(stages, "stages");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.estate = estate;
    this.coverage = Objects.requireNonNull(coverage, "coverage");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.staleness = Objects.requireNonNull(staleness, "staleness");
  }

  /**
   * Reconciles now, stores the report, and returns it.
   *
   * <p>If the estate cannot be listed the report says so and covers only the onboarded
   * repositories; it never reports "nothing was missed" on the strength of a failed listing.
   *
   * @return the report
   */
  public CoverageReport reconcile() {
    Instant now = clock.instant();
    List<String> warnings = new ArrayList<>();

    Map<String, OnboardedRepository> onboarded = new TreeMap<>();
    for (OnboardedRepository r : registry.all()) {
      OnboardedRepository earlier = onboarded.putIfAbsent(key(r.repository()), r);
      if (earlier != null) {
        warnings.add(
            "Repositories onboarded under names that differ only by case, treated as one: "
                + earlier.repository()
                + " and "
                + r.repository());
      }
    }

    Map<String, String> inEstate = listEstate(warnings); // key -> identifier; null if unavailable
    boolean estateAvailable = inEstate != null;

    List<RepositoryCoverage> result = new ArrayList<>();
    for (Map.Entry<String, OnboardedRepository> entry : onboarded.entrySet()) {
      result.add(
          classify(
              entry.getValue().repository(),
              now,
              estateAvailable ? inEstate.containsKey(entry.getKey()) : null));
    }
    if (estateAvailable) {
      List<String> goneFromEstate =
          onboarded.entrySet().stream()
              .filter(e -> !inEstate.containsKey(e.getKey()))
              .map(e -> e.getValue().repository())
              .toList();
      if (!goneFromEstate.isEmpty()) {
        warnings.add(
            "Onboarded but not found in the estate (renamed, archived or deleted?): "
                + goneFromEstate);
      }
      inEstate.forEach(
          (k, identifier) -> {
            if (!onboarded.containsKey(k)) {
              result.add(
                  new RepositoryCoverage(
                      identifier,
                      CoverageState.NEVER_ONBOARDED,
                      List.of("in the source-control estate but not in the registry"),
                      null,
                      null,
                      false,
                      true));
            }
          });
    }
    result.sort(
        Comparator.comparing((RepositoryCoverage c) -> key(c.repository()))
            .thenComparing(RepositoryCoverage::repository));

    CoverageReport report =
        new CoverageReport(now, estateAvailable, withStages(result, warnings), warnings);
    coverage.replace(report);
    return report;
  }

  /**
   * The latest stored report, flagged stale if it is old.
   *
   * @return the report, or empty value if no reconciliation has completed
   */
  public Trended<java.util.Optional<CoverageReport>> latest() {
    return staleness.wrap(coverage.latest());
  }

  // ---- classification --------------------------------------------------------------------------

  /**
   * Shows each repository's rollout stage. If the stages cannot be read the report says so and
   * shows none, rather than failing a reconciliation that is otherwise sound.
   */
  private List<RepositoryCoverage> withStages(
      List<RepositoryCoverage> repositories, List<String> warnings) {
    Map<String, RolloutStage> byKey = new TreeMap<>();
    try {
      stages.currentStages().forEach((name, stage) -> byKey.put(key(name), stage));
    } catch (RuntimeException e) {
      warnings.add("Rollout stages could not be read, so none are shown: " + e.getMessage());
      return repositories;
    }
    return repositories.stream()
        .map(r -> r.withRolloutStage(byKey.get(key(r.repository()))))
        .toList();
  }

  private RepositoryCoverage classify(String repository, Instant now, Boolean inEstate) {
    List<RunRecord> recent = runs.recentForRepository(repository, settings.degradedWindow());
    if (recent.isEmpty()) {
      return new RepositoryCoverage(
          repository,
          CoverageState.SILENT,
          List.of("no run has ever been received"),
          null,
          null,
          true,
          inEstate);
    }
    RunRecord latest = recent.get(0);
    Instant lastReceived = latest.receivedAt() != null ? latest.receivedAt() : latest.committedAt();
    String toolVersion = latest.toolVersion();

    if (lastReceived == null
        || Duration.between(lastReceived, now).compareTo(settings.cadence()) > 0) {
      return new RepositoryCoverage(
          repository,
          CoverageState.SILENT,
          List.of(
              lastReceived == null
                  ? "the last run has no recorded time"
                  : "no run received since "
                      + lastReceived
                      + " (cadence window "
                      + settings.cadence()
                      + ")"),
          lastReceived,
          toolVersion,
          true,
          inEstate);
    }

    List<String> reasons = new ArrayList<>();
    boolean degraded = consistentlyDegraded(recent);
    if (degraded) {
      reasons.add(
          "the last "
              + settings.degradedWindow()
              + " runs were all degraded or analysed with shallow history");
    }
    boolean drifted = drifted(toolVersion, reasons);

    CoverageState state =
        degraded
            ? CoverageState.DEGRADED_REPORTING
            : drifted ? CoverageState.VERSION_DRIFT : CoverageState.REPORTING;
    return new RepositoryCoverage(
        repository, state, reasons, lastReceived, toolVersion, true, inEstate);
  }

  private boolean consistentlyDegraded(List<RunRecord> recent) {
    if (recent.size() < settings.degradedWindow()) {
      return false; // too little history to call it consistent
    }
    return recent.stream().allMatch(r -> r.degraded() || "SHALLOW".equals(r.historyDepth()));
  }

  private boolean drifted(String toolVersion, List<String> reasons) {
    Integer major = majorOf(toolVersion);
    if (major == null) {
      reasons.add("the tool version is not recognisable, so it cannot be judged current");
      return true;
    }
    if (major != settings.currentToolMajor()) {
      reasons.add(
          "tool version "
              + toolVersion
              + " is major "
              + major
              + ", not the current major "
              + settings.currentToolMajor());
      return true;
    }
    return false;
  }

  static Integer majorOf(String version) {
    if (version == null) {
      return null;
    }
    int end = 0;
    while (end < version.length() && Character.isDigit(version.charAt(end))) {
      end++;
    }
    if (end == 0 || end > 9) {
      return null;
    }
    return Integer.parseInt(version.substring(0, end));
  }

  // ---- estate ----------------------------------------------------------------------------------

  /** Lower-cased identifier to the identifier as the estate spells it, or null if unavailable. */
  private Map<String, String> listEstate(List<String> warnings) {
    if (estate == null) {
      warnings.add(
          "No source-control estate is configured, so repositories that were never onboarded cannot be found.");
      return null;
    }
    try {
      Map<String, String> byKey = new TreeMap<>();
      for (String identifier : estate.repositories()) {
        byKey.putIfAbsent(key(identifier), identifier);
      }
      if (byKey.isEmpty()) {
        warnings.add(
            "The estate listing was empty. If that is not expected, check the credentials and what they can see.");
      }
      return byKey;
    } catch (EstateUnavailableException e) {
      warnings.add(
          "The source-control estate could not be listed ("
              + e.getMessage()
              + "), so repositories that were never onboarded cannot be found.");
      return null;
    }
  }

  private static String key(String identifier) {
    return identifier.toLowerCase(Locale.ROOT);
  }
}
