package com.debthunter.controlplane.store.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

abstract class EvidenceAccessLogContract {

  private static final Instant T0 = Instant.parse("2026-07-01T12:00:00Z");

  protected abstract EvidenceAccessLog newLog();

  private static EvidenceAccess access(Instant at, String caller, EvidenceAccess.Outcome outcome) {
    return new EvidenceAccess(
        at,
        caller,
        "run-1",
        outcome == EvidenceAccess.Outcome.NOT_FOUND ? null : "acme/widgets",
        "investigating a regression",
        outcome);
  }

  @Test
  void entriesComeBackOldestFirstWithEveryFieldIntact() {
    EvidenceAccessLog log = newLog();
    log.record(access(T0, "alice", EvidenceAccess.Outcome.GRANTED));
    log.record(access(T0.plusSeconds(5), "bob", EvidenceAccess.Outcome.DENIED));
    log.record(access(T0.plusSeconds(9), "carol", EvidenceAccess.Outcome.NOT_FOUND));

    List<EvidenceAccess> all = log.since(T0);

    assertThat(all).extracting(EvidenceAccess::caller).containsExactly("alice", "bob", "carol");
    assertThat(all.get(0)).isEqualTo(access(T0, "alice", EvidenceAccess.Outcome.GRANTED));
    assertThat(all.get(2).repository()).isNull();
  }

  @Test
  void sinceLeavesOutEarlierEntries() {
    EvidenceAccessLog log = newLog();
    log.record(access(T0, "alice", EvidenceAccess.Outcome.GRANTED));
    log.record(access(T0.plusSeconds(60), "bob", EvidenceAccess.Outcome.GRANTED));

    assertThat(log.since(T0.plusSeconds(1)))
        .extracting(EvidenceAccess::caller)
        .containsExactly("bob");
    assertThat(log.since(T0.plusSeconds(61))).isEmpty();
  }

  @Test
  void aLimitCapsTheCountAndKeepsTheOldestFirst() {
    EvidenceAccessLog log = newLog();
    log.record(access(T0, "alice", EvidenceAccess.Outcome.GRANTED));
    log.record(access(T0.plusSeconds(5), "bob", EvidenceAccess.Outcome.GRANTED));
    log.record(access(T0.plusSeconds(9), "carol", EvidenceAccess.Outcome.GRANTED));

    assertThat(log.since(T0, 2)).extracting(EvidenceAccess::caller).containsExactly("alice", "bob");
    assertThat(log.since(T0.plusSeconds(1), 5))
        .extracting(EvidenceAccess::caller)
        .containsExactly("bob", "carol");
  }

  @Test
  void theLogOffersNoWayToChangeOrRemoveAnEntry() {
    List<String> names =
        Arrays.stream(EvidenceAccessLog.class.getMethods()).map(Method::getName).toList();

    assertThat(names).containsOnly("record", "since");
  }
}
