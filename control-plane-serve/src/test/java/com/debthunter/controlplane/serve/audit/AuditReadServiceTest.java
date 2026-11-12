package com.debthunter.controlplane.serve.audit;

import static com.debthunter.controlplane.serve.ServeFixtures.CLOCK;
import static com.debthunter.controlplane.serve.ServeFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.serve.access.AccessDeniedException;
import com.debthunter.controlplane.serve.access.Caller;
import com.debthunter.controlplane.serve.access.InvalidRequestException;
import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.serve.access.ServiceUnavailableException;
import com.debthunter.controlplane.store.audit.AuditEntry;
import com.debthunter.controlplane.store.audit.AuditEntry.Kind;
import com.debthunter.controlplane.store.audit.AuditEntry.Outcome;
import com.debthunter.controlplane.store.audit.AuditTrail;
import com.debthunter.controlplane.store.audit.InMemoryAuditTrail;
import com.debthunter.controlplane.store.evidence.EvidenceAccess;
import com.debthunter.controlplane.store.evidence.InMemoryEvidenceAccessLog;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditReadServiceTest {

  private final InMemoryAuditTrail trail = new InMemoryAuditTrail();
  private final InMemoryEvidenceAccessLog evidence = new InMemoryEvidenceAccessLog();
  private final AuditReadService service = new AuditReadService(trail, evidence, CLOCK);
  private final Caller auditor = new Caller("ada", Set.of(Roles.AUDITOR), Set.of());

  private void admin(int i) {
    trail.record(
        new AuditEntry(Kind.ADMIN, NOW.plusSeconds(i), "admin", "A" + i, "t", "", Outcome.ALLOWED));
  }

  @Test
  void onlyAnAuditorMayRead() {
    for (Caller other :
        List.of(
            new Caller("a", Set.of(Roles.SQUAD), Set.of("team-a")),
            new Caller("b", Set.of(Roles.LEADER), Set.of()),
            new Caller("c", Set.of(Roles.EVIDENCE), Set.of("team-a")),
            new Caller("d", Set.of(), Set.of()))) {
      assertThatThrownBy(() -> service.read(other, AuditReadService.Trail.ADMIN, null, null))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void aTrailIsReadFromItsStartByDefault() {
    admin(0);
    admin(1);

    AuditPage page = service.read(auditor, AuditReadService.Trail.ADMIN, null, null);

    assertThat(page.trail()).isEqualTo(AuditReadService.Trail.ADMIN);
    assertThat(page.items()).extracting(AuditPage.AuditLine::action).containsExactly("A0", "A1");
    assertThat(page.nextFrom()).isNull();
    assertThat(page.generatedAt()).isEqualTo(NOW);
    assertThat(page.items().get(0).outcome()).isEqualTo("ALLOWED");
  }

  @Test
  void aFullPagePointsToWhereTheNextStartsAndAShortOneDoesNot() {
    for (int i = 0; i < 5; i++) {
      admin(i);
    }

    AuditPage full = service.read(auditor, AuditReadService.Trail.ADMIN, null, 3);
    AuditPage rest = service.read(auditor, AuditReadService.Trail.ADMIN, full.nextFrom(), 3);

    assertThat(full.items()).hasSize(3);
    assertThat(full.nextFrom()).isEqualTo(NOW.plusSeconds(2));
    assertThat(rest.items())
        .extracting(AuditPage.AuditLine::action)
        .containsExactly("A2", "A3", "A4");
    assertThat(service.read(auditor, AuditReadService.Trail.ADMIN, null, 6).nextFrom()).isNull();
  }

  @Test
  void theLimitBoundsAreExact() {
    assertThatThrownBy(() -> service.read(auditor, AuditReadService.Trail.ADMIN, null, 0))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> service.read(auditor, AuditReadService.Trail.ADMIN, null, -1))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(
            () ->
                service.read(
                    auditor, AuditReadService.Trail.ADMIN, null, AuditReadService.MAX_PAGE + 1))
        .isInstanceOf(InvalidRequestException.class);
    service.read(auditor, AuditReadService.Trail.ADMIN, null, 1);
    service.read(auditor, AuditReadService.Trail.ADMIN, null, AuditReadService.MAX_PAGE);
  }

  @Test
  void theDefaultPageIsOneHundred() {
    for (int i = 0; i < AuditReadService.DEFAULT_PAGE + 20; i++) {
      admin(i);
    }

    assertThat(service.read(auditor, AuditReadService.Trail.ADMIN, null, null).items())
        .hasSize(AuditReadService.DEFAULT_PAGE);
  }

  @Test
  void eachTrailReadsOnlyItsOwnEntries() {
    admin(0);
    trail.record(
        new AuditEntry(Kind.SUPPRESSION, NOW, "alice", "SUPPRESS", "r fp", "why", Outcome.DENIED));

    assertThat(service.read(auditor, AuditReadService.Trail.SUPPRESSION, null, null).items())
        .extracting(AuditPage.AuditLine::caller)
        .containsExactly("alice");
    assertThat(service.read(auditor, AuditReadService.Trail.REIDENTIFICATION, null, null).items())
        .isEmpty();
  }

  @Test
  void evidenceRetrievalsAreShownWithTheRunAndRepositoryAndAMissingRunHasNoRepository() {
    evidence.record(
        new EvidenceAccess(
            NOW, "ivy", "r1", "acme/widgets", "a good reason", EvidenceAccess.Outcome.GRANTED));
    evidence.record(
        new EvidenceAccess(
            NOW.plusSeconds(1),
            "ivy",
            "nope",
            null,
            "a good reason",
            EvidenceAccess.Outcome.NOT_FOUND));

    AuditPage page = service.read(auditor, AuditReadService.Trail.EVIDENCE, null, null);

    assertThat(page.items())
        .containsExactly(
            new AuditPage.AuditLine(
                NOW, "ivy", "RETRIEVE_EVIDENCE", "r1 acme/widgets", "a good reason", "GRANTED"),
            new AuditPage.AuditLine(
                NOW.plusSeconds(1),
                "ivy",
                "RETRIEVE_EVIDENCE",
                "nope",
                "a good reason",
                "NOT_FOUND"));
  }

  @Test
  void fromLeavesOutEarlierEntries() {
    admin(0);
    admin(10);

    assertThat(
            service.read(auditor, AuditReadService.Trail.ADMIN, NOW.plusSeconds(5), null).items())
        .extracting(AuditPage.AuditLine::action)
        .containsExactly("A10");
  }

  @Test
  void aTrailThatCannotBeReadIsUnavailableNotAnEmptyAnswer() {
    AuditTrail down =
        new AuditTrail() {
          @Override
          public void record(AuditEntry entry) {}

          @Override
          public List<AuditEntry> read(Kind kind, Instant from, int limit) {
            throw new IllegalStateException("down");
          }
        };
    AuditReadService broken = new AuditReadService(down, evidence, CLOCK);

    assertThatThrownBy(() -> broken.read(auditor, AuditReadService.Trail.ADMIN, null, null))
        .isInstanceOf(ServiceUnavailableException.class);
  }
}
