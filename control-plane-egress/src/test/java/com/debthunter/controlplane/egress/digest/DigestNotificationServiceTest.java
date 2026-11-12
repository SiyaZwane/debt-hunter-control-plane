package com.debthunter.controlplane.egress.digest;

import static com.debthunter.controlplane.egress.digest.DigestHarness.GADGETS;
import static com.debthunter.controlplane.egress.digest.DigestHarness.TEAM_A;
import static com.debthunter.controlplane.egress.digest.DigestHarness.TEAM_B;
import static com.debthunter.controlplane.egress.digest.DigestHarness.THING;
import static com.debthunter.controlplane.egress.digest.DigestHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.domain.Severity;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-70 and the rules around it: one digest per squad per period, built from that squad only.
 */
class DigestNotificationServiceTest {

  private DigestHarness h;

  @BeforeEach
  void setUp() {
    h = InMemoryDigestParts.harness();
    h.slackFor(TEAM_A);
    h.teamsFor(TEAM_B);
  }

  private DigestMessage only(RecordingChannel channel) {
    assertThat(channel.posted).hasSize(1);
    return channel.posted.get(0).message();
  }

  @Test
  void theFirstPeriodIsOnlyABaselineAndSendsNothing() {
    h.newFinding(WIDGETS, "fp-0", Severity.HIGH, "src/Old.java", "old");
    h.start();
    h.pump();

    h.nextPeriod();
    h.pump();

    assertThat(h.slack.posted).isEmpty();
    assertThat(h.teams.posted).isEmpty();
  }

  @Test
  void fiveFindingsInOnePeriodMakeOneDigest() {
    h.start();
    for (int i = 1; i <= 5; i++) {
      h.newFinding(WIDGETS, "fp-" + i, Severity.HIGH, "src/F" + i + ".java", "msg " + i);
    }

    h.nextPeriod();
    h.pump();

    DigestMessage digest = only(h.slack);
    assertThat(digest.newBySeverity()).containsEntry("HIGH", 5);
    assertThat(digest.squad()).isEqualTo(TEAM_A);
    assertThat(digest.distinctFiles()).isEqualTo(5);
    assertThat(h.teams.posted).as("team-b had nothing happen").isEmpty();
  }

  @Test
  void aDigestCountsNewResolvedAndRegressed() {
    h.start();
    h.newFinding(WIDGETS, "fp-1", Severity.CRITICAL, "src/A.java", "a");
    h.newFinding(WIDGETS, "fp-2", Severity.LOW, "src/B.java", "b");
    h.transition(
        WIDGETS,
        "fp-3",
        com.debthunter.controlplane.store.lifecycle.FindingState.OPEN,
        com.debthunter.controlplane.store.lifecycle.FindingState.RESOLVED,
        com.debthunter.controlplane.store.lifecycle.TransitionCause.ABSENT_FROM_COMPLETE_RUN,
        "run-x");
    h.run("run-r", WIDGETS, DigestHarness.finding("fp-4", Severity.MEDIUM, "src/R.java", "back"));
    h.transition(
        WIDGETS,
        "fp-4",
        com.debthunter.controlplane.store.lifecycle.FindingState.RESOLVED,
        com.debthunter.controlplane.store.lifecycle.FindingState.REGRESSED,
        com.debthunter.controlplane.store.lifecycle.TransitionCause.REAPPEARED_AFTER_RESOLUTION,
        "run-r");

    h.nextPeriod();
    h.pump();

    DigestMessage d = only(h.slack);
    assertThat(d.newBySeverity())
        .containsOnly(java.util.Map.entry("CRITICAL", 1), java.util.Map.entry("LOW", 1));
    assertThat(d.resolved()).isEqualTo(1);
    assertThat(d.regressed()).isEqualTo(1);
    assertThat(d.notable()).extracting(DigestMessage.Notable::regression).contains(true);
  }

  @Test
  void eachSquadGetsItsOwnDigestWithOnlyItsOwnRepositories() {
    h.start();
    h.newFinding(WIDGETS, "fp-a", Severity.HIGH, "src/A.java", "mine");
    h.newFinding(THING, "fp-b", Severity.HIGH, "src/B.java", "theirs");

    h.nextPeriod();
    h.pump();

    assertThat(only(h.slack).notable()).hasSize(1);
    assertThat(only(h.slack).notable().get(0).message()).isEqualTo("mine");
    assertThat(only(h.teams).notable()).hasSize(1);
    assertThat(only(h.teams).notable().get(0).message()).isEqualTo("theirs");
    assertThat(h.slack.posted.get(0).webhook())
        .isEqualTo(java.net.URI.create("https://hooks.example/team-a/SLACK"));
  }

  @Test
  void aPeriodWithNothingNewSendsNothingUnlessAsked() {
    h.start();
    h.nextPeriod();
    h.pump();
    assertThat(h.slack.posted).isEmpty();

    DigestHarness verbose =
        InMemoryDigestParts.harness(
            new DigestSettings(Duration.ofDays(1), Duration.ofDays(7), 5, 10, 200, true));
    verbose.slackFor(TEAM_A);
    verbose.start();
    verbose.nextPeriod();
    verbose.pump();
    assertThat(verbose.slack.posted).hasSize(1);
    assertThat(verbose.slack.posted.get(0).message().isEmpty()).isTrue();
  }

  @Test
  void eventsAfterThePeriodClosesBelongToTheNextDigest() {
    h.start();
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "first");
    h.nextPeriod();
    h.pump();
    h.newFinding(WIDGETS, "fp-2", Severity.HIGH, "src/B.java", "second");
    assertThat(h.slack.posted).hasSize(1);

    h.nextPeriod();
    h.pump();

    assertThat(h.slack.posted).hasSize(2);
    assertThat(h.slack.posted.get(0).message().notable())
        .extracting(DigestMessage.Notable::message)
        .containsExactly("first");
    assertThat(h.slack.posted.get(1).message().notable())
        .extracting(DigestMessage.Notable::message)
        .containsExactly("second");
  }

  @Test
  void tickingAgainInTheSamePeriodChangesNothing() {
    h.start();
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");
    h.nextPeriod();
    h.pump();

    for (int i = 0; i < 5; i++) {
      assertThat(h.service.tick()).isZero();
      h.pump();
    }

    assertThat(h.slack.posted).hasSize(1);
  }

  @Test
  void twoInstancesOverTheSameLedgerSendOneDigest() {
    h.start();
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");
    h.nextPeriod();
    DigestNotificationService second =
        new DigestNotificationService(
            h.parts.registry(),
            h.parts.log(),
            h.parts.ledger(),
            h.builder,
            h.outbox,
            DigestSettings.defaults(),
            h.clock);

    h.service.tick();
    second.tick();
    h.pump();

    assertThat(h.slack.posted).hasSize(1);
  }

  @Test
  void aRestartedServiceDoesNotRebuildAPeriodItAlreadyClosed() {
    h.start();
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");
    h.nextPeriod();
    h.pump();
    DigestNotificationService restarted =
        new DigestNotificationService(
            h.parts.registry(),
            h.parts.log(),
            h.parts.ledger(),
            h.builder,
            h.outbox,
            DigestSettings.defaults(),
            h.clock);

    restarted.tick();
    h.pump();

    assertThat(h.slack.posted).hasSize(1);
  }

  @Test
  void aLongListIsCappedAndSaysHowManyMore() {
    h.start();
    for (int i = 1; i <= 8; i++) {
      h.newFinding(WIDGETS, "fp-" + i, Severity.HIGH, "src/F" + i + ".java", "m" + i);
    }

    h.nextPeriod();
    h.pump();

    DigestMessage d = only(h.slack);
    assertThat(d.notable()).hasSize(5);
    assertThat(d.notableOmitted()).isEqualTo(3);
    assertThat(d.distinctFiles()).isEqualTo(8);
  }

  @Test
  void worstAndReturningFindingsAreListedFirst() {
    h.start();
    h.newFinding(WIDGETS, "fp-low", Severity.LOW, "src/A.java", "low");
    h.newFinding(WIDGETS, "fp-crit", Severity.CRITICAL, "src/C.java", "crit");
    h.newFinding(WIDGETS, "fp-high", Severity.HIGH, "src/H.java", "high");

    h.nextPeriod();
    h.pump();

    assertThat(only(h.slack).notable())
        .extracting(DigestMessage.Notable::severity)
        .containsExactly("CRITICAL", "HIGH", "LOW");
  }

  @Test
  void theStandingPictureShowsOpenFindingsAndEachRepositorysStage() {
    InMemoryDigestParts.FakeOpen open = new InMemoryDigestParts.FakeOpen();
    open.byRepository.put(WIDGETS, new java.util.EnumMap<>(java.util.Map.of(Severity.HIGH, 4)));
    open.byRepository.put(
        GADGETS, new java.util.EnumMap<>(java.util.Map.of(Severity.HIGH, 1, Severity.LOW, 2)));
    com.debthunter.controlplane.egress.MutableClock clock =
        new com.debthunter.controlplane.egress.MutableClock();
    DigestHarness with = new DigestHarness(InMemoryDigestParts.create(clock, open), clock);
    with.slackFor(TEAM_A);
    with.start();
    with.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");

    with.nextPeriod();
    with.pump();

    DigestMessage d = with.slack.posted.get(0).message();
    assertThat(d.openBySeverity()).containsEntry("HIGH", 5).containsEntry("LOW", 2);
    assertThat(d.stages())
        .extracting(DigestMessage.StageLine::repository, DigestMessage.StageLine::stage)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(GADGETS, "STAGE_1"),
            org.assertj.core.groups.Tuple.tuple(WIDGETS, "STAGE_3"));
    assertThat(d.link()).isEqualTo("https://cp.example/squads/team-a");
  }

  @Test
  void aLongRepositoryListIsCutAndSaysHowManyAreLeftOut() {
    h.start();
    for (int i = 0; i < 12; i++) {
      h.register("acme/r" + i, TEAM_A);
    }
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");

    h.nextPeriod();
    h.pump();

    DigestMessage d = only(h.slack);
    assertThat(d.stages()).hasSize(10);
    assertThat(d.stagesOmitted()).isEqualTo(4);
  }

  @Test
  void aRepositoryNotInTheRolloutIsSaidToBe() {
    h.start();
    h.stages.remove(WIDGETS);
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");

    h.nextPeriod();
    h.pump();

    assertThat(only(h.slack).stages())
        .extracting(DigestMessage.StageLine::stage)
        .contains("not in rollout");
  }

  @Test
  void aFindingMissingFromItsRunIsLeftOutRatherThanBreakingTheDigest() {
    h.start();
    h.newFinding(WIDGETS, "fp-ok", Severity.HIGH, "src/A.java", "ok");
    h.transition(
        WIDGETS,
        "fp-ghost",
        null,
        com.debthunter.controlplane.store.lifecycle.FindingState.NEW,
        com.debthunter.controlplane.store.lifecycle.TransitionCause.FIRST_OBSERVED,
        "run-nowhere");

    h.nextPeriod();
    h.pump();

    assertThat(only(h.slack).notable()).hasSize(1);
  }

  @Test
  void aSquadWithNoChannelHasItsDigestKeptAndSentOnceAChannelIsSet() {
    DigestHarness bare = InMemoryDigestParts.harness();
    bare.start();
    bare.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");
    bare.nextPeriod();
    bare.pump();
    assertThat(bare.slack.posted).isEmpty();
    assertThat(bare.parts.queue().counts(DigestQueue.KIND).pending()).isEqualTo(1);
    assertThat(bare.parts.queue().counts(DigestQueue.KIND).dead()).isZero();

    bare.slackFor(TEAM_A);
    bare.clock.advance(Duration.ofSeconds(8));
    bare.pump();

    assertThat(bare.slack.posted).hasSize(1);
  }

  @Test
  void aCrashBetweenQueuingAndNotingItDoesNotQueueTheDigestTwice() {
    h.start();
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");
    h.nextPeriod();
    java.util.concurrent.atomic.AtomicBoolean crash =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    DigestLedger flaky =
        new DigestLedger() {
          private final DigestLedger real = h.parts.ledger();

          @Override
          public Window window(java.time.Instant s, java.time.Instant e, long l) {
            return real.window(s, e, l);
          }

          @Override
          public boolean record(
              String o, java.time.Instant p, String payload, java.time.Instant n) {
            return real.record(o, p, payload, n);
          }

          @Override
          public java.util.Optional<Entry> find(String o, java.time.Instant p) {
            return real.find(o, p);
          }

          @Override
          public java.util.List<Entry> unqueued() {
            return real.unqueued();
          }

          @Override
          public void markQueued(String o, java.time.Instant p, java.time.Instant n) {
            if (crash.getAndSet(false)) {
              throw new IllegalStateException("died before noting it");
            }
            real.markQueued(o, p, n);
          }

          @Override
          public void markSent(String o, java.time.Instant p, java.time.Instant n) {
            real.markSent(o, p, n);
          }
        };
    DigestNotificationService service =
        new DigestNotificationService(
            h.parts.registry(),
            h.parts.log(),
            flaky,
            h.builder,
            h.outbox,
            DigestSettings.defaults(),
            h.clock);

    org.assertj.core.api.Assertions.assertThatThrownBy(service::tick)
        .isInstanceOf(IllegalStateException.class);
    service.tick();

    assertThat(h.parts.queue().counts(DigestQueue.KIND).pending()).isEqualTo(1);
  }

  @Test
  void eventsAfterTheWindowWasFixedAreLeftForTheNextDigest() {
    h.start();
    h.newFinding(WIDGETS, "fp-in", Severity.HIGH, "src/In.java", "in");
    h.nextPeriod();
    long day = 86_400;
    java.time.Instant current =
        java.time.Instant.ofEpochSecond(
            Math.floorDiv(h.clock.instant().getEpochSecond(), day) * day);
    // The window was fixed (say by another instance) before the next finding arrived.
    h.parts.ledger().window(current.minus(Duration.ofDays(1)), current, h.parts.log().latestSeq());
    h.newFinding(WIDGETS, "fp-after", Severity.HIGH, "src/After.java", "after");

    h.pump();

    assertThat(only(h.slack).notable())
        .extracting(DigestMessage.Notable::message)
        .containsExactly("in");
  }

  @Test
  void aPeriodAlreadyClosedIsNotWorkedOutAgainOnLaterTicks() {
    java.util.concurrent.atomic.AtomicInteger reads =
        new java.util.concurrent.atomic.AtomicInteger();
    com.debthunter.controlplane.store.lifecycle.TransitionLogRepository counting =
        new com.debthunter.controlplane.store.lifecycle.TransitionLogRepository() {
          private final com.debthunter.controlplane.store.lifecycle.TransitionLogRepository real =
              h.parts.log();

          @Override
          public void append(
              java.util.List<com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent>
                  e) {
            real.append(e);
          }

          @Override
          public java.util.List<com.debthunter.controlplane.store.lifecycle.SequencedTransition>
              after(long a, int l) {
            reads.incrementAndGet();
            return real.after(a, l);
          }

          @Override
          public long latestSeq() {
            reads.incrementAndGet();
            return real.latestSeq();
          }

          @Override
          public java.util.List<com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent>
              historyOf(String r, String s, String f) {
            return real.historyOf(r, s, f);
          }

          @Override
          public java.util.List<com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent>
              eventsOfRun(String r) {
            return real.eventsOfRun(r);
          }
        };
    DigestNotificationService service =
        new DigestNotificationService(
            h.parts.registry(),
            counting,
            h.parts.ledger(),
            h.builder,
            h.outbox,
            DigestSettings.defaults(),
            h.clock);
    service.tick();
    int afterFirst = reads.get();

    service.tick();
    service.tick();

    assertThat(afterFirst).isPositive();
    assertThat(reads.get()).isEqualTo(afterFirst);
  }
}
