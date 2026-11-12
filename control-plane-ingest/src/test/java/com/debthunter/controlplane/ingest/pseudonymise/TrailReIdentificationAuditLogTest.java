package com.debthunter.controlplane.ingest.pseudonymise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.audit.AuditEntry;
import com.debthunter.controlplane.store.audit.AuditEntry.Kind;
import com.debthunter.controlplane.store.audit.AuditEntry.Outcome;
import com.debthunter.controlplane.store.audit.AuditTrail;
import com.debthunter.controlplane.store.audit.InMemoryAuditTrail;
import com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory;
import com.debthunter.controlplane.store.identity.Pseudonym;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrailReIdentificationAuditLogTest {

  private static final Instant T = Instant.parse("2026-07-01T12:00:00Z");
  private static final Pseudonym P = Pseudonym.fromStored("a".repeat(64));

  @Test
  void grantedAndDeniedAttemptsAreKeptWithWhoWhomAndWhyButNeverTheIdentity() {
    InMemoryAuditTrail trail = new InMemoryAuditTrail();
    TrailReIdentificationAuditLog log = new TrailReIdentificationAuditLog(trail);

    log.record(
        new ReIdentificationAuditEntry(
            "officer", P, "case 42", T, ReIdentificationAuditEntry.Outcome.GRANTED));
    log.record(
        new ReIdentificationAuditEntry(
            "intruder", P, "", T, ReIdentificationAuditEntry.Outcome.DENIED));

    assertThat(trail.read(Kind.REIDENTIFICATION, Instant.EPOCH, 10))
        .containsExactly(
            new AuditEntry(
                Kind.REIDENTIFICATION,
                T,
                "officer",
                "REIDENTIFY",
                P.value(),
                "case 42",
                Outcome.ALLOWED),
            new AuditEntry(
                Kind.REIDENTIFICATION, T, "intruder", "REIDENTIFY", P.value(), "", Outcome.DENIED));
  }

  @Test
  void ifTheTrailIsDownNothingIsDisclosed() {
    AuditTrail down =
        new AuditTrail() {
          @Override
          public void record(AuditEntry entry) {
            throw new IllegalStateException("down");
          }

          @Override
          public List<AuditEntry> read(Kind kind, Instant from, int limit) {
            return List.of();
          }
        };
    InMemoryPseudonymDirectory directory = new InMemoryPseudonymDirectory();
    directory.remember(P, "alice@example.com");
    ReIdentificationService service =
        new ReIdentificationService(
            directory, new TrailReIdentificationAuditLog(down), Clock.fixed(T, ZoneOffset.UTC));

    assertThatThrownBy(
            () ->
                service.reIdentify(
                    new ReIdentificationRequest(
                        "officer", Set.of(ReIdentificationService.AUTHORITY), P, "case 42")))
        .isInstanceOf(IllegalStateException.class);
  }
}
