package com.debthunter.controlplane.egress.digest;

import static com.debthunter.controlplane.egress.digest.DigestHarness.TEAM_A;
import static com.debthunter.controlplane.egress.digest.DigestHarness.THING;
import static com.debthunter.controlplane.egress.digest.DigestHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.domain.Severity;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CP-AC-72: suppressions ending within seven days are in the squad's digest. */
class CpAc72_SuppressionExpiryInDigestTest {

  private DigestHarness h;

  @BeforeEach
  void setUp() {
    h = InMemoryDigestParts.harness();
    h.slackFor(TEAM_A);
    h.start();
    h.nextPeriod();
  }

  @Test
  void aSuppressionEndingInThreeDaysIsListedWithItsReason() {
    h.suppression(
        WIDGETS, "fp-1", "vendor patch pending", h.clock.instant().plus(Duration.ofDays(3)));

    h.service.tick();
    h.pump();

    assertThat(h.slack.posted).hasSize(1);
    DigestMessage d = h.slack.posted.get(0).message();
    assertThat(d.expiring()).hasSize(1);
    assertThat(d.expiring().get(0).reason()).isEqualTo("vendor patch pending");
    assertThat(d.expiring().get(0).repository()).isEqualTo(WIDGETS);
    assertThat(d.expiring().get(0).endsOn())
        .isEqualTo(h.clock.instant().plus(Duration.ofDays(3)).toString());
  }

  @Test
  void oneEndingInEightDaysOrAlreadyEndedIsNot() {
    h.suppression(WIDGETS, "fp-late", "later", h.clock.instant().plus(Duration.ofDays(8)));
    h.suppression(WIDGETS, "fp-gone", "gone", h.clock.instant().minus(Duration.ofDays(1)));
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");

    h.service.tick();
    h.pump();

    assertThat(h.slack.posted.get(0).message().expiring()).isEmpty();
  }

  @Test
  void aSuppressionEndingSoonIsNewsOnItsOwn() {
    h.suppression(WIDGETS, "fp-1", "soon", h.clock.instant().plus(Duration.ofDays(1)));

    h.service.tick();
    h.pump();

    assertThat(h.slack.posted).as("nothing else happened, but the digest is still sent").hasSize(1);
  }

  @Test
  void anotherSquadsSuppressionIsNotListed() {
    h.suppression(THING, "fp-b", "theirs", h.clock.instant().plus(Duration.ofDays(2)));

    h.service.tick();
    h.pump();

    assertThat(h.slack.posted).isEmpty();
  }
}
