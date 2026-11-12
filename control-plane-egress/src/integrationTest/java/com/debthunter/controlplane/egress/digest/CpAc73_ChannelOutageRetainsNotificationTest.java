package com.debthunter.controlplane.egress.digest;

import static com.debthunter.controlplane.egress.digest.DigestHarness.TEAM_A;
import static com.debthunter.controlplane.egress.digest.DigestHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.domain.Severity;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-73: while the channel is down the notification is kept, and it is sent once on recovery.
 */
@Tag("integration")
class CpAc73_ChannelOutageRetainsNotificationTest {

  private static DigestHarness squadWithAFinding() {
    DigestHarness h = JdbcDigestParts.harness();
    h.slackFor(TEAM_A);
    h.start();
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");
    h.nextPeriod();
    return h;
  }

  @Test
  void anOutageKeepsTheDigestAndRecoverySendsItOnceAsItWasBuilt() {
    DigestHarness h = squadWithAFinding();
    h.slack.down = true;

    for (int i = 0; i < 8; i++) {
      h.pump();
      h.clock.advance(Duration.ofSeconds(8));
    }
    var held = h.parts.queue().counts(DigestQueue.KIND);
    assertThat(h.slack.posted).isEmpty();
    assertThat(held.pending()).isEqualTo(1);
    assertThat(held.dead()).as("an outage never sets a digest aside").isZero();
    assertThat(h.alerts.alerts).isEmpty();

    // A finding arrives while the channel is down: it belongs to the next digest, not this one.
    h.newFinding(WIDGETS, "fp-late", Severity.LOW, "src/Late.java", "late");
    h.slack.down = false;
    h.pump();

    assertThat(h.slack.posted).hasSize(1);
    assertThat(h.slack.posted.get(0).message().notable())
        .extracting(DigestMessage.Notable::message)
        .containsExactly("x");
    assertThat(h.parts.queue().counts(DigestQueue.KIND).pending()).isZero();
  }

  @Test
  void aWebhookThatIsRevokedEndsInASetAsideDigestAndAnAlertNotASilentDrop() {
    DigestHarness h = squadWithAFinding();
    h.slack.refuse = true;

    for (int i = 0; i < 6; i++) {
      h.pump();
      h.clock.advance(Duration.ofSeconds(8));
    }

    assertThat(h.parts.queue().counts(DigestQueue.KIND).dead()).isEqualTo(1);
    assertThat(h.alerts.alerts).hasSize(1);
    assertThat(h.alerts.alerts.get(0).kind()).isEqualTo(DigestQueue.KIND);
    assertThat(h.slack.posted).isEmpty();
  }
}
