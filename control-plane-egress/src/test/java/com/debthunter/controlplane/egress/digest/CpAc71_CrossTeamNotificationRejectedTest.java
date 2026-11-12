package com.debthunter.controlplane.egress.digest;

import static com.debthunter.controlplane.egress.digest.DigestHarness.GADGETS;
import static com.debthunter.controlplane.egress.digest.DigestHarness.TEAM_A;
import static com.debthunter.controlplane.egress.digest.DigestHarness.THING;
import static com.debthunter.controlplane.egress.digest.DigestHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.domain.Severity;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** CP-AC-71: a digest for one squad cannot carry another squad's repositories. */
class CpAc71_CrossTeamNotificationRejectedTest {

  private static final Instant START = Instant.parse("2026-07-01T00:00:00Z");
  private static final Instant END = Instant.parse("2026-07-02T00:00:00Z");

  @Test
  void aRequestNamingAnotherSquadsRepositoryIsRejected() {
    DigestHarness h = InMemoryDigestParts.harness();
    h.newFinding(THING, "fp-b", Severity.CRITICAL, "src/Secret.java", "theirs");

    assertThatThrownBy(
            () ->
                h.service.digestFor(
                    TEAM_A, Set.of(WIDGETS, THING), START, END, 0, h.parts.log().latestSeq()))
        .isInstanceOf(CrossSquadException.class)
        .hasMessageContaining(THING);
  }

  @Test
  void aRepositoryNobodyOwnsIsRejectedToo() {
    DigestHarness h = InMemoryDigestParts.harness();

    assertThatThrownBy(() -> h.service.digestFor(TEAM_A, Set.of("ghost/repo"), START, END, 0, 0))
        .isInstanceOf(CrossSquadException.class);
  }

  @Test
  void aSquadsOwnRepositoriesAreAccepted() {
    DigestHarness h = InMemoryDigestParts.harness();
    h.newFinding(WIDGETS, "fp-a", Severity.HIGH, "src/A.java", "mine");
    h.newFinding(THING, "fp-b", Severity.CRITICAL, "src/B.java", "theirs");

    DigestMessage d =
        h.service.digestFor(
            TEAM_A, Set.of(WIDGETS, GADGETS), START, END, 0, h.parts.log().latestSeq());

    assertThat(d.notable()).extracting(DigestMessage.Notable::message).containsExactly("mine");
  }

  @Test
  void aSquadsScheduledDigestNeverContainsAnotherSquadsFindings() {
    DigestHarness h = InMemoryDigestParts.harness();
    h.slackFor(TEAM_A);
    h.teamsFor(DigestHarness.TEAM_B);
    h.start();
    h.newFinding(THING, "fp-b", Severity.CRITICAL, "src/Secret.java", "theirs");

    h.nextPeriod();
    h.pump();

    assertThat(h.slack.posted).isEmpty();
    assertThat(h.teams.posted).hasSize(1);
  }
}
