package com.debthunter.controlplane.store.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class TransitionLogContract {

  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

  protected abstract TransitionLogRepository newLog();

  private TransitionLogRepository log;

  @BeforeEach
  void createLog() {
    log = newLog();
  }

  private static FindingTransitionEvent event(
      String id, String runId, String fp, FindingState from, FindingState to) {
    return new FindingTransitionEvent(
        id, "acme/widgets", "default", fp, from, to, runId, AT, TransitionCause.OBSERVED_AGAIN);
  }

  @Test
  void theLogOffersNoWayToUpdateOrDeleteAnEvent() {
    List<String> names =
        Arrays.stream(TransitionLogRepository.class.getMethods()).map(Method::getName).toList();

    // Reading forward (after) is fine; the point is that nothing can change or remove an event.
    assertThat(names).containsExactlyInAnyOrder("append", "after", "historyOf", "eventsOfRun");
    assertThat(names)
        .noneMatch(n -> n.startsWith("update") || n.startsWith("delete") || n.startsWith("remove"));
  }

  @Test
  void eventsComeBackInTheOrderTheyWereAppended() {
    log.append(
        List.of(
            event("e1", "r1", "fp-a", null, FindingState.NEW),
            event("e2", "r2", "fp-a", FindingState.NEW, FindingState.OPEN),
            event("e3", "r3", "fp-a", FindingState.OPEN, FindingState.RESOLVED)));

    assertThat(log.historyOf("acme/widgets", "default", "fp-a"))
        .extracting(FindingTransitionEvent::toState)
        .containsExactly(FindingState.NEW, FindingState.OPEN, FindingState.RESOLVED);
  }

  @Test
  void appendingAnEventWhoseIdIsAlreadyPresentIsANoOp() {
    FindingTransitionEvent e = event("e1", "r1", "fp-a", null, FindingState.NEW);

    log.append(List.of(e));
    log.append(List.of(e));

    assertThat(log.historyOf("acme/widgets", "default", "fp-a")).hasSize(1);
  }

  @Test
  void aRetriedBatchThatAlreadyPartlyLandedAddsOnlyWhatWasMissing() {
    FindingTransitionEvent first = event("e1", "r1", "fp-a", null, FindingState.NEW);
    FindingTransitionEvent second = event("e2", "r1", "fp-b", null, FindingState.NEW);
    log.append(List.of(first));

    log.append(List.of(first, second));

    assertThat(log.eventsOfRun("r1")).containsExactly(first, second);
  }

  @Test
  void historyIsKeptPerFindingAndPerRun() {
    log.append(
        List.of(
            event("e1", "r1", "fp-a", null, FindingState.NEW),
            event("e2", "r1", "fp-b", null, FindingState.NEW)));

    assertThat(log.historyOf("acme/widgets", "default", "fp-a")).hasSize(1);
    assertThat(log.eventsOfRun("r1")).hasSize(2);
    assertThat(log.eventsOfRun("nope")).isEmpty();
  }

  @Test
  void anEventForAnIllegalTransitionCannotEvenBeConstructed() {
    assertThatThrownBy(() -> event("e1", "r1", "fp-a", FindingState.RESOLVED, FindingState.OPEN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event("e2", "r1", "fp-a", null, FindingState.RESOLVED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event("e3", "r1", "fp-a", FindingState.OPEN, FindingState.NEW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aConsumerCanFollowTheLogForwardFromAPosition() {
    log.append(
        List.of(
            event("e1", "r1", "fp-a", null, FindingState.NEW),
            event("e2", "r2", "fp-a", FindingState.NEW, FindingState.OPEN),
            event("e3", "r3", "fp-a", FindingState.OPEN, FindingState.RESOLVED)));

    List<SequencedTransition> all = log.after(0, 10);

    assertThat(all).extracting(t -> t.event().eventId()).containsExactly("e1", "e2", "e3");
    assertThat(all).extracting(SequencedTransition::seq).isSorted().doesNotHaveDuplicates();
    assertThat(log.after(all.get(0).seq(), 10))
        .extracting(t -> t.event().eventId())
        .containsExactly("e2", "e3");
    assertThat(log.after(all.get(2).seq(), 10)).isEmpty();
  }

  @Test
  void followingTheLogRespectsTheLimitAndCanBePagedToTheEnd() {
    log.append(
        List.of(
            event("e1", "r1", "fp-a", null, FindingState.NEW),
            event("e2", "r2", "fp-a", FindingState.NEW, FindingState.OPEN),
            event("e3", "r3", "fp-a", FindingState.OPEN, FindingState.RESOLVED)));

    List<SequencedTransition> page1 = log.after(0, 2);
    List<SequencedTransition> page2 = log.after(page1.get(1).seq(), 2);

    assertThat(page1).hasSize(2);
    assertThat(page2).extracting(t -> t.event().eventId()).containsExactly("e3");
  }

  @Test
  void aRetriedAppendDoesNotMakeTheConsumerSeeAnEventTwice() {
    FindingTransitionEvent e1 = event("e1", "r1", "fp-a", null, FindingState.NEW);
    log.append(List.of(e1));
    log.append(List.of(e1));

    assertThat(log.after(0, 10)).hasSize(1);
  }
}
