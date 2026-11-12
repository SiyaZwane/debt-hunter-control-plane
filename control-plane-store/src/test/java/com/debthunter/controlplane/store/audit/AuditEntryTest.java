package com.debthunter.controlplane.store.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.audit.AuditEntry.Kind;
import com.debthunter.controlplane.store.audit.AuditEntry.Outcome;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuditEntryTest {

  private static final Instant T = Instant.parse("2026-07-01T12:00:00Z");

  @Test
  void textCannotCarryAForgedSecondLine() {
    AuditEntry e =
        new AuditEntry(Kind.ADMIN, T, "a\nb\r", "X\tY", "t\u0000t", "d\ne", Outcome.ALLOWED);

    assertThat(e.caller()).isEqualTo("a b ");
    assertThat(e.action()).isEqualTo("X Y");
    assertThat(e.target()).isEqualTo("t t");
    assertThat(e.detail()).isEqualTo("d e");
  }

  @Test
  void oversizedTextIsCutAtTheLimit() {
    AuditEntry e =
        new AuditEntry(
            Kind.ADMIN,
            T,
            "c".repeat(AuditEntry.MAX_FIELD + 50),
            "a",
            "t",
            "d".repeat(AuditEntry.MAX_DETAIL + 50),
            Outcome.ALLOWED);

    assertThat(e.caller()).hasSize(AuditEntry.MAX_FIELD + 1).endsWith("…");
    assertThat(e.detail()).hasSize(AuditEntry.MAX_DETAIL + 1).endsWith("…");
  }

  @Test
  void textExactlyAtTheLimitIsKeptWhole() {
    AuditEntry e =
        new AuditEntry(
            Kind.ADMIN,
            T,
            "c".repeat(AuditEntry.MAX_FIELD),
            "a",
            "t",
            "d".repeat(AuditEntry.MAX_DETAIL),
            Outcome.ALLOWED);

    assertThat(e.caller()).hasSize(AuditEntry.MAX_FIELD).doesNotEndWith("…");
    assertThat(e.detail()).hasSize(AuditEntry.MAX_DETAIL).doesNotEndWith("…");
  }

  @Test
  void aMissingDetailIsEmptyAndTheRestAreRequired() {
    assertThat(new AuditEntry(Kind.ADMIN, T, "c", "a", "t", null, Outcome.ALLOWED).detail())
        .isEmpty();
    assertThatThrownBy(() -> new AuditEntry(Kind.ADMIN, T, null, "a", "t", "", Outcome.ALLOWED))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AuditEntry(Kind.ADMIN, null, "c", "a", "t", "", Outcome.ALLOWED))
        .isInstanceOf(NullPointerException.class);
  }
}
