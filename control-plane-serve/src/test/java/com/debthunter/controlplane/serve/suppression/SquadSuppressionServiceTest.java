package com.debthunter.controlplane.serve.suppression;

import static com.debthunter.controlplane.serve.ServeFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.serve.access.AccessDeniedException;
import com.debthunter.controlplane.serve.access.Caller;
import com.debthunter.controlplane.serve.access.InvalidRequestException;
import com.debthunter.controlplane.serve.access.NotFoundException;
import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.serve.access.ServiceUnavailableException;
import com.debthunter.controlplane.store.audit.AuditEntry;
import com.debthunter.controlplane.store.audit.AuditEntry.Kind;
import com.debthunter.controlplane.store.audit.AuditEntry.Outcome;
import com.debthunter.controlplane.store.audit.AuditTrail;
import com.debthunter.controlplane.store.audit.InMemoryAuditTrail;
import com.debthunter.controlplane.store.registry.InMemoryRepositoryRegistry;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.store.suppressions.InMemorySuppressionRepository;
import com.debthunter.controlplane.store.suppressions.SuppressionRegisterService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SquadSuppressionServiceTest {

  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String REASON = "accepted until the module is retired";

  private final InMemoryRepositoryRegistry registry = new InMemoryRepositoryRegistry();
  private final InMemorySuppressionRepository repository = new InMemorySuppressionRepository();
  private final SuppressionRegisterService register =
      new SuppressionRegisterService(repository, event -> {});
  private final InMemoryAuditTrail audit = new InMemoryAuditTrail();
  private SquadSuppressionService service;
  private final Caller alice = new Caller("alice", Set.of(Roles.SQUAD), Set.of("team-a"));

  @BeforeEach
  void setUp() {
    registry.save(
        new OnboardedRepository(
            "acme/widgets",
            "team-a",
            "tier-1",
            "1.0.0",
            "STAGE_3",
            new WorkItemRoute("jira", "DH")));
    registry.save(
        new OnboardedRepository(
            "other/thing",
            "team-b",
            "tier-1",
            "1.0.0",
            "STAGE_3",
            new WorkItemRoute("jira", "DH")));
    service = new SquadSuppressionService(registry, register, audit, 90, CLOCK);
  }

  private static SuppressRequest request(String repository, String fingerprint, Instant expiry) {
    return new SuppressRequest(repository, fingerprint, REASON, expiry);
  }

  private static Instant in(long days) {
    return NOW.plus(Duration.ofDays(days));
  }

  private List<AuditEntry> trail() {
    return audit.read(Kind.SUPPRESSION, Instant.EPOCH, 100);
  }

  @Test
  void aSquadSuppressesInItsOwnRepositoryAndTheGrantIsRecordedFirst() {
    SuppressionView view =
        service.suppress(alice, "team-a", request("acme/widgets", "fp1", in(30)));

    assertThat(view.repository()).isEqualTo("acme/widgets");
    assertThat(view.createdAt()).isEqualTo(NOW);
    assertThat(view.expiresAt()).isEqualTo(in(30));
    assertThat(register.isSuppressed("acme/widgets", "fp1", NOW)).isTrue();
    assertThat(trail())
        .containsExactly(
            new AuditEntry(
                Kind.SUPPRESSION,
                NOW,
                "alice",
                "SUPPRESS",
                "acme/widgets fp1",
                REASON,
                Outcome.ALLOWED));
  }

  @Test
  void theReasonIsTrimmedAndKeptWithinItsLimit() {
    String longReason = "x".repeat(SquadSuppressionService.MAX_REASON + 40);

    SuppressionView view =
        service.suppress(
            alice,
            "team-a",
            new SuppressRequest("acme/widgets", "fp1", "  " + longReason + "  ", in(5)));

    assertThat(view.reason()).hasSize(SquadSuppressionService.MAX_REASON);
  }

  @Test
  void aReasonOfExactlyTheMinimumLengthIsAcceptedAndOneShorterIsNot() {
    String exactly = "x".repeat(SquadSuppressionService.MIN_REASON);

    service.suppress(alice, "team-a", new SuppressRequest("acme/widgets", "fp1", exactly, in(5)));
    assertThatThrownBy(
            () ->
                service.suppress(
                    alice,
                    "team-a",
                    new SuppressRequest("acme/widgets", "fp2", exactly.substring(1), in(5))))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(
            () ->
                service.suppress(
                    alice,
                    "team-a",
                    new SuppressRequest(
                        "acme/widgets", "fp2", "   " + exactly.substring(1) + "   ", in(5))))
        .as("padding does not count")
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void aMissingOrBlankReasonIsRefused() {
    assertThatThrownBy(
            () ->
                service.suppress(
                    alice, "team-a", new SuppressRequest("acme/widgets", "fp1", null, in(5))))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(
            () ->
                service.suppress(
                    alice,
                    "team-a",
                    new SuppressRequest("acme/widgets", "fp1", "          ", in(5))))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void anotherSquadsRepositoryAnUnknownOneAndANonMemberSquadAreAllRefusedAndRecorded() {
    assertThatThrownBy(
            () -> service.suppress(alice, "team-a", request("other/thing", "fp1", in(5))))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.suppress(alice, "team-a", request("nope/none", "fp1", in(5))))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> service.suppress(alice, "team-b", request("other/thing", "fp1", in(5))))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.suppress(alice, null, request("acme/widgets", "fp1", in(5))))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(trail())
        .hasSize(4)
        .allSatisfy(e -> assertThat(e.outcome()).isEqualTo(Outcome.DENIED));
    assertThat(register.isSuppressed("other/thing", "fp1", NOW)).isFalse();
  }

  @Test
  void anotherRoleIsRefusedBeforeAnythingIsLookedAtOrRecorded() {
    Caller leader = new Caller("lena", Set.of(Roles.LEADER), Set.of("team-a"));

    assertThatThrownBy(
            () -> service.suppress(leader, "team-a", request("acme/widgets", "fp1", in(5))))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(trail()).isEmpty();
  }

  @Test
  void theExpiryBoundariesAreExact() {
    assertThatThrownBy(
            () -> service.suppress(alice, "team-a", request("acme/widgets", "fp1", null)))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> service.suppress(alice, "team-a", request("acme/widgets", "fp1", NOW)))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(
            () ->
                service.suppress(
                    alice, "team-a", request("acme/widgets", "fp1", in(90).plusMillis(1))))
        .isInstanceOf(InvalidRequestException.class);

    service.suppress(alice, "team-a", request("acme/widgets", "fp1", NOW.plusMillis(1)));
    service.suppress(alice, "team-a", request("acme/widgets", "fp2", in(90)));
  }

  @Test
  void aRequestMissingItsRepositoryOrFingerprintOrWithOversizedOnesIsInvalid() {
    for (SuppressRequest bad :
        List.of(
            request(null, "fp1", in(5)),
            request(" ", "fp1", in(5)),
            request("acme/widgets", null, in(5)),
            request("acme/widgets", "", in(5)),
            request("acme/widgets", "f".repeat(201), in(5)),
            request("a".repeat(301), "fp1", in(5)))) {
      assertThatThrownBy(() -> service.suppress(alice, "team-a", bad))
          .isInstanceOf(InvalidRequestException.class);
    }
    service.suppress(alice, "team-a", request("acme/widgets", "f".repeat(200), in(5)));
  }

  @Test
  void ifTheGrantCannotBeRecordedNothingIsGranted() {
    AuditTrail down = new DownTrail();
    SquadSuppressionService broken =
        new SquadSuppressionService(registry, register, down, 90, CLOCK);

    assertThatThrownBy(
            () -> broken.suppress(alice, "team-a", request("acme/widgets", "fp1", in(5))))
        .isInstanceOf(ServiceUnavailableException.class);

    assertThat(register.isSuppressed("acme/widgets", "fp1", NOW)).isFalse();
  }

  @Test
  void aRefusalThatCannotBeRecordedIsStillARefusal() {
    SquadSuppressionService broken =
        new SquadSuppressionService(registry, register, new DownTrail(), 90, CLOCK);

    assertThatThrownBy(() -> broken.suppress(alice, "team-a", request("other/thing", "fp1", in(5))))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aSquadWithdrawsItsOwnSuppression() {
    SuppressionView view =
        service.suppress(alice, "team-a", request("acme/widgets", "fp1", in(30)));

    service.revoke(alice, "team-a", view.id());

    assertThat(register.isSuppressed("acme/widgets", "fp1", NOW)).isFalse();
    assertThat(trail())
        .extracting(AuditEntry::action, AuditEntry::outcome)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("SUPPRESS", Outcome.ALLOWED),
            org.assertj.core.groups.Tuple.tuple("REVOKE_SUPPRESSION", Outcome.ALLOWED));
  }

  @Test
  void aSquadCannotWithdrawAnotherSquadsSuppressionOrOneThatDoesNotExist() {
    Caller bob = new Caller("bob", Set.of(Roles.SQUAD), Set.of("team-b"));
    SuppressionView theirs = service.suppress(bob, "team-b", request("other/thing", "fp1", in(30)));

    assertThatThrownBy(() -> service.revoke(alice, "team-a", theirs.id()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.revoke(alice, "team-a", "nope"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.revoke(alice, "team-b", theirs.id()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.revoke(alice, null, theirs.id()))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(register.isSuppressed("other/thing", "fp1", NOW)).isTrue();
    assertThat(trail().stream().filter(e -> e.outcome() == Outcome.DENIED)).hasSize(4);
  }

  @Test
  void aWithdrawalThatCannotBeRecordedWithdrawsNothing() {
    SuppressionView view =
        service.suppress(alice, "team-a", request("acme/widgets", "fp1", in(30)));
    SquadSuppressionService broken =
        new SquadSuppressionService(registry, register, new DownTrail(), 90, CLOCK);

    assertThatThrownBy(() -> broken.revoke(alice, "team-a", view.id()))
        .isInstanceOf(ServiceUnavailableException.class);

    assertThat(register.isSuppressed("acme/widgets", "fp1", NOW)).isTrue();
  }

  @Test
  void aRoleOtherThanSquadCannotWithdraw() {
    SuppressionView view =
        service.suppress(alice, "team-a", request("acme/widgets", "fp1", in(30)));
    Caller leader = new Caller("lena", Set.of(Roles.LEADER), Set.of("team-a"));

    assertThatThrownBy(() -> service.revoke(leader, "team-a", view.id()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void theCapMustBePositive() {
    assertThatThrownBy(() -> new SquadSuppressionService(registry, register, audit, 0, CLOCK))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static final class DownTrail implements AuditTrail {
    @Override
    public void record(AuditEntry entry) {
      throw new IllegalStateException("audit store down");
    }

    @Override
    public List<AuditEntry> read(Kind kind, Instant from, int limit) {
      return List.of();
    }
  }
}
