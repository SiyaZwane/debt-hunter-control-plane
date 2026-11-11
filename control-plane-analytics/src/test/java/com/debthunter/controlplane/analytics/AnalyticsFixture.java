package com.debthunter.controlplane.analytics;

import com.debthunter.controlplane.analytics.drift.ArchitectureDriftTracker;
import com.debthunter.controlplane.analytics.projection.MetricProjector;
import com.debthunter.controlplane.analytics.readmodel.ReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.Staleness;
import com.debthunter.controlplane.analytics.remediation.RemediationEffectivenessCalculator;
import com.debthunter.controlplane.analytics.trend.TrendEngine;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import java.time.Duration;
import java.time.Instant;

/** A pipeline, a projector and every engine over one read model. */
public final class AnalyticsFixture {

  public static final Instant FOREVER = Instant.parse("2100-01-01T00:00:00Z");

  public final PipelineDriver pipeline;
  public final ReadModelRepository readModel;
  public final MutableClock clock = new MutableClock(Days.day(100));
  public final MetricProjector projector;
  public final TrendEngine trends;
  public final ArchitectureDriftTracker drift;
  public final RemediationEffectivenessCalculator remediation;

  public AnalyticsFixture(PipelineDriver pipeline, ReadModelRepository readModel) {
    this.pipeline = pipeline;
    this.readModel = readModel;
    this.projector =
        new MetricProjector(
            pipeline.transitions, pipeline.runs, pipeline.findings, readModel, clock, 100);
    Staleness staleness = new Staleness(readModel, clock, Duration.ofMinutes(15));
    this.trends = new TrendEngine(readModel, staleness);
    this.drift = new ArchitectureDriftTracker(readModel, staleness);
    this.remediation = new RemediationEffectivenessCalculator(readModel, staleness);
  }

  public static AnalyticsFixture inMemory() {
    return new AnalyticsFixture(
        PipelineDriver.inMemory(),
        new com.debthunter.controlplane.analytics.readmodel.InMemoryReadModelRepository());
  }

  /** Applies a run and brings the read models up to date. */
  public void applyAndProject(RunBundleBuilder run) {
    pipeline.apply(run);
    projector.project();
  }
}
