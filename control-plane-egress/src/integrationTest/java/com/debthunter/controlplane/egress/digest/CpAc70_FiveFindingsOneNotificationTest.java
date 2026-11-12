package com.debthunter.controlplane.egress.digest;

import static com.debthunter.controlplane.egress.digest.DigestHarness.TEAM_A;
import static com.debthunter.controlplane.egress.digest.DigestHarness.TEAM_B;
import static com.debthunter.controlplane.egress.digest.DigestHarness.THING;
import static com.debthunter.controlplane.egress.digest.DigestHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.domain.Severity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-70: five findings in a period reach the squad as one notification, never five. */
@Tag("integration")
class CpAc70_FiveFindingsOneNotificationTest {

  @Test
  void fiveNewFindingsAreOneNotificationEvenIfTheServiceIsAskedManyTimes() {
    DigestHarness h = JdbcDigestParts.harness();
    h.slackFor(TEAM_A);
    h.teamsFor(TEAM_B);
    h.start();
    for (int i = 1; i <= 5; i++) {
      h.newFinding(WIDGETS, "fp-" + i, Severity.values()[i % 3], "src/F" + i + ".java", "m" + i);
    }
    h.newFinding(THING, "fp-other", Severity.HIGH, "src/T.java", "theirs");

    h.nextPeriod();
    for (int i = 0; i < 5; i++) {
      h.pump();
      h.service.tick();
    }

    assertThat(h.slack.posted).hasSize(1);
    assertThat(h.slack.posted.get(0).message().notable()).hasSize(5);
    assertThat(h.teams.posted).hasSize(1);
    assertThat(h.teams.posted.get(0).message().notable()).hasSize(1);
    assertThat(h.parts.queue().counts(DigestQueue.KIND).pending()).isZero();
    assertThat(h.alerts.alerts).isEmpty();
  }

  @Test
  void aSecondInstanceAndARestartDoNotSendItAgain() {
    DigestHarness h = JdbcDigestParts.harness();
    h.slackFor(TEAM_A);
    h.start();
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");
    h.nextPeriod();
    h.pump();

    DigestNotificationService other =
        new DigestNotificationService(
            h.parts.registry(),
            h.parts.log(),
            h.parts.ledger(),
            h.builder,
            h.outbox,
            DigestSettings.defaults(),
            h.clock);
    other.tick();
    h.pump();

    assertThat(h.slack.posted).hasSize(1);
  }

  @Test
  void theStandingPictureComesFromTheSystemOfRecord() {
    DigestHarness h = JdbcDigestParts.harness();
    h.slackFor(TEAM_A);
    h.start();
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");
    h.nextPeriod();
    h.pump();

    assertThat(h.slack.posted.get(0).message().openBySeverity())
        .as("no finding_state rows were written by this test, so none are open")
        .isEmpty();
  }
}
