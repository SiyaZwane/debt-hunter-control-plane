package com.debthunter.controlplane.analytics;

import static com.debthunter.controlplane.analytics.AnalyticsFixture.FOREVER;
import static com.debthunter.controlplane.analytics.Days.REPO;
import static com.debthunter.controlplane.analytics.Days.day;
import static com.debthunter.controlplane.analytics.Days.run;
import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class ProjectorSurvivesRestartOnPostgresTest {

  @Test
  void aRestartedProjectorResumesFromItsCheckpointAndNothingIsCountedTwice() {
    DataSource dataSource = PostgresAnalytics.migratedDatabase();
    AnalyticsFixture before = PostgresAnalytics.over(dataSource);
    before.applyAndProject(
        run(0).finding("a", "r", "HIGH", "STATIC_ANALYSIS", "A.java", null, null));
    before.applyAndProject(
        run(1).finding("a", "r", "HIGH", "STATIC_ANALYSIS", "A.java", null, null));

    // A new process: new projector, new repositories, only the database carried over.
    AnalyticsFixture after = PostgresAnalytics.over(dataSource);
    assertThat(after.projector.project()).as("nothing new to project").isZero();
    after.applyAndProject(run(2));

    var rates = after.trends.findingRates(REPO, "default", day(0), FOREVER).value();
    assertThat(rates.created()).isEqualTo(1);
    assertThat(rates.resolved()).isEqualTo(1);
    assertThat(after.trends.severityOverTime(REPO, "default", day(0), FOREVER).value()).hasSize(3);
  }
}
