package com.debthunter.controlplane.store.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.audit.AuditEntry.Kind;
import com.debthunter.controlplane.store.audit.AuditEntry.Outcome;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

abstract class AuditTrailContract {

  private static final Instant T0 = Instant.parse("2026-07-01T12:00:00Z");

  /** A trail that can also be purged. */
  protected abstract AuditTrail newTrail();

  private static AuditEntry entry(Kind kind, Instant at, String caller) {
    return new AuditEntry(kind, at, caller, "SUPPRESS", "acme/widgets fp", "why", Outcome.ALLOWED);
  }

  @Test
  void entriesComeBackOldestFirstWithEveryFieldIntact() {
    AuditTrail trail = newTrail();
    trail.record(entry(Kind.SUPPRESSION, T0, "alice"));
    trail.record(
        new AuditEntry(
            Kind.SUPPRESSION, T0.plusSeconds(5), "bob", "SUPPRESS", "t", "d", Outcome.DENIED));

    assertThat(trail.read(Kind.SUPPRESSION, T0, 10))
        .containsExactly(
            entry(Kind.SUPPRESSION, T0, "alice"),
            new AuditEntry(
                Kind.SUPPRESSION, T0.plusSeconds(5), "bob", "SUPPRESS", "t", "d", Outcome.DENIED));
  }

  @Test
  void eachKindHasItsOwnTrail() {
    AuditTrail trail = newTrail();
    trail.record(entry(Kind.ADMIN, T0, "admin"));
    trail.record(entry(Kind.REIDENTIFICATION, T0, "officer"));

    assertThat(trail.read(Kind.ADMIN, T0, 10))
        .extracting(AuditEntry::caller)
        .containsExactly("admin");
    assertThat(trail.read(Kind.REIDENTIFICATION, T0, 10))
        .extracting(AuditEntry::caller)
        .containsExactly("officer");
    assertThat(trail.read(Kind.SUPPRESSION, T0, 10)).isEmpty();
  }

  @Test
  void fromLeavesOutEarlierEntriesAndLimitCapsTheCount() {
    AuditTrail trail = newTrail();
    for (int i = 0; i < 5; i++) {
      trail.record(entry(Kind.ADMIN, T0.plusSeconds(i), "c" + i));
    }

    assertThat(trail.read(Kind.ADMIN, T0.plusSeconds(2), 10))
        .extracting(AuditEntry::caller)
        .containsExactly("c2", "c3", "c4");
    assertThat(trail.read(Kind.ADMIN, T0, 2))
        .extracting(AuditEntry::caller)
        .containsExactly("c0", "c1");
  }

  @Test
  void deletingBeforeACutOffRemovesOnlyOlderEntries() {
    AuditTrail trail = newTrail();
    trail.record(entry(Kind.ADMIN, T0, "old"));
    trail.record(entry(Kind.ADMIN, T0.plusSeconds(100), "new"));

    int removed = ((AuditPurger) trail).deleteBefore(T0.plusSeconds(50));

    assertThat(removed).isEqualTo(1);
    assertThat(trail.read(Kind.ADMIN, T0, 10))
        .extracting(AuditEntry::caller)
        .containsExactly("new");
  }

  @Test
  void anEntryExactlyAtTheCutOffIsKept() {
    AuditTrail trail = newTrail();
    trail.record(entry(Kind.ADMIN, T0.plusSeconds(50), "edge"));

    assertThat(((AuditPurger) trail).deleteBefore(T0.plusSeconds(50))).isZero();
    assertThat(trail.read(Kind.ADMIN, T0, 10)).hasSize(1);
  }

  @Test
  void theTrailOffersNoWayToChangeOrRemoveAnEntry() {
    assertThat(Arrays.stream(AuditTrail.class.getMethods()).map(Method::getName).toList())
        .containsExactlyInAnyOrder("record", "read");
  }
}
