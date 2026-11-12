package com.debthunter.controlplane.egress.digest;

import static com.debthunter.controlplane.egress.digest.DigestHarness.TEAM_A;
import static com.debthunter.controlplane.egress.digest.DigestHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.debthunter.domain.Severity;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DigestSettingsAndWorkerTest {

  @Test
  void settingsRefuseNonsense() {
    assertThatThrownBy(
            () -> new DigestSettings(Duration.ofSeconds(5), Duration.ofDays(7), 5, 10, 200, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new DigestSettings(Duration.ofDays(1), Duration.ZERO, 5, 10, 200, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new DigestSettings(Duration.ofDays(1), Duration.ofDays(7), -1, 10, 200, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(DigestSettings.defaults().cadence()).isEqualTo(Duration.ofDays(1));
    assertThat(DigestSettings.defaults().sendEmpty()).isFalse();
  }

  @Test
  void aChannelNeedsAnOwnerAndAReference() {
    assertThatThrownBy(() -> new SquadChannel(" ", SquadChannel.Kind.SLACK, "r"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SquadChannel("a", SquadChannel.Kind.SLACK, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aDigestWithOnlyTheStandingPictureIsEmpty() {
    DigestHarness h = InMemoryDigestParts.harness();
    DigestMessage d =
        h.builder.build(
            TEAM_A, java.util.Set.of(WIDGETS), h.clock.instant(), h.clock.instant(), 0, 0);

    assertThat(d.isEmpty()).isTrue();
    assertThat(d.stages()).hasSize(1);
  }

  @Test
  void theBackgroundThreadBuildsAndDeliversWhenAPeriodCloses() {
    DigestHarness h = InMemoryDigestParts.harness();
    h.slackFor(TEAM_A);
    h.start();
    h.worker.start();
    h.worker.start();
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");

    h.nextPeriod();

    await().atMost(Duration.ofSeconds(10)).until(() -> h.slack.posted.size() == 1);
    h.worker.close();
    h.worker.close();
    assertThat(h.worker.runOnce()).isZero();
  }

  @Test
  void aStoppedWorkerLeavesTheDigestToBeBuiltWhenItStartsAgain() {
    DigestHarness h = InMemoryDigestParts.harness();
    h.slackFor(TEAM_A);
    h.start();
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");
    h.nextPeriod();
    assertThat(h.slack.posted).isEmpty();

    h.pump();

    assertThat(h.slack.posted).hasSize(1);
    assertThat(h.service.cadence()).isEqualTo(Duration.ofDays(1));
  }
}
