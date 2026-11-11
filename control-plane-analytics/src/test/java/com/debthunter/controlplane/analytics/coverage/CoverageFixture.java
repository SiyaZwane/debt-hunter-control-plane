package com.debthunter.controlplane.analytics.coverage;

import com.debthunter.controlplane.analytics.Days;
import com.debthunter.controlplane.analytics.MutableClock;
import com.debthunter.controlplane.analytics.PipelineDriver;
import com.debthunter.controlplane.analytics.coverage.estate.SourceControlEstateProvider;
import com.debthunter.controlplane.analytics.readmodel.Staleness;
import com.debthunter.controlplane.store.registry.InMemoryRepositoryRegistry;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.store.rollout.InMemoryStageRepository;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageEvent;
import com.debthunter.controlplane.store.rollout.StageRecord;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A registry, a pipeline, an estate and a tracker, with the calendar under the test's control. */
public final class CoverageFixture {

  public static final Duration CADENCE = Duration.ofDays(7);

  public final RepositoryRegistryService registry;
  public final PipelineDriver pipeline;
  public final List<String> estate = new ArrayList<>();
  public final MutableClock clock = new MutableClock(Days.day(100));
  public final CoverageRepository coverage;
  public final CoverageTracker tracker;
  public final InMemoryStageRepository stages = new InMemoryStageRepository();
  private boolean estateDown;
  private boolean stagesDown;

  public CoverageFixture() {
    this(
        new InMemoryRepositoryRegistry(),
        PipelineDriver.inMemory(),
        new InMemoryCoverageRepository(),
        0);
  }

  public CoverageFixture(
      RepositoryRegistry registryStore,
      PipelineDriver pipeline,
      CoverageRepository coverage,
      int currentToolMajor) {
    this.registry = new RepositoryRegistryService(registryStore);
    this.pipeline = pipeline;
    this.coverage = coverage;
    SourceControlEstateProvider provider =
        () -> {
          if (estateDown) {
            throw new com.debthunter.controlplane.analytics.coverage.estate
                .EstateUnavailableException("GitHub could not be reached");
          }
          return List.copyOf(estate);
        };
    this.tracker =
        new CoverageTracker(
            registry,
            pipeline.runs,
            provider,
            coverage,
            new CoverageSettings(CADENCE, currentToolMajor, 3),
            clock,
            new Staleness(
                () -> coverage.latest().map(CoverageReport::reconciledAt),
                clock,
                Duration.ofHours(36)),
            () -> {
              if (stagesDown) {
                throw new IllegalStateException("stage store unavailable");
              }
              return stages.currentStages();
            });
  }

  /** Puts a repository into the rollout at a stage. */
  public CoverageFixture inRollout(String repository, RolloutStage stage) {
    java.time.Instant at = Days.day(1);
    stages.apply(
        new StageRecord(repository, stage, at, "team", java.util.Map.of(), null, null),
        new StageEvent(
            repository,
            StageEvent.Kind.ENROLLED,
            null,
            stage,
            at,
            "lead",
            java.util.Map.of(),
            null));
    return this;
  }

  /** Makes the stage store fail. */
  public CoverageFixture stagesDown() {
    this.stagesDown = true;
    return this;
  }

  /** Onboards a repository. */
  public CoverageFixture onboard(String repository) {
    registry.onboard(
        new OnboardedRepository(
            repository, "team", "tier-1", "1.0", "enforce", new WorkItemRoute("jira", "X")));
    return this;
  }

  /** Makes the estate contain a repository. */
  public CoverageFixture inEstate(String... repositories) {
    estate.addAll(List.of(repositories));
    return this;
  }

  public CoverageFixture estateDown() {
    estateDown = true;
    return this;
  }

  /** A run for a repository, received on a given day. */
  public static RunBundleBuilder run(String repository, int day, String id) {
    return RunBundleBuilder.run()
        .id(id)
        .repository(repository)
        .commit("c-" + id)
        .timestamp(Days.day(day).toString());
  }

  /** Sets "now" to a day. */
  public CoverageFixture today(int day) {
    clock.set(Days.day(day));
    return this;
  }

  public CoverageFixture apply(RunBundleBuilder run) {
    pipeline.apply(run);
    return this;
  }

  public RepositoryCoverage of(CoverageReport report, String repository) {
    return report.repositories().stream()
        .filter(r -> r.repository().equals(repository))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no coverage for " + repository));
  }

  public static Instant day(int n) {
    return Days.day(n);
  }
}
