package com.debthunter.controlplane.ingest.pseudonymise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.ingest.pseudonymise.ReIdentificationAuditEntry.Outcome;
import com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory;
import com.debthunter.controlplane.store.identity.Pseudonym;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReIdentificationServiceTest {

  private static final Instant NOW = Instant.parse("2026-05-05T10:00:00Z");

  private final InMemoryPseudonymDirectory directory = new InMemoryPseudonymDirectory();
  private final Pseudonym alice =
      new IdentityPseudonymiser(new InMemoryPseudonymKeyStore(Keys.sequential()), directory)
          .pseudonymise("alice@example.com");
  private final List<ReIdentificationAuditEntry> trail = new ArrayList<>();
  private final ReIdentificationService service =
      new ReIdentificationService(directory, trail::add, Clock.fixed(NOW, ZoneOffset.UTC));

  private ReIdentificationRequest request(Set<String> authorities, String justification) {
    return new ReIdentificationRequest("auditor-1", authorities, alice, justification);
  }

  @Test
  void aCallerWithTheAuthorityAndAJustificationLearnsTheIdentity() {
    assertThat(service.reIdentify(request(Set.of("REIDENTIFICATION"), "HR case 4471")))
        .contains("alice@example.com");
  }

  @Test
  void aCallerWithoutTheAuthorityIsRefusedAndLearnsNothing() {
    assertThatThrownBy(() -> service.reIdentify(request(Set.of("READ"), "curious")))
        .isInstanceOf(ReIdentificationDeniedException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("alice"));
  }

  @Test
  void aCallerWithNoJustificationIsRefused() {
    assertThatThrownBy(() -> service.reIdentify(request(Set.of("REIDENTIFICATION"), "  ")))
        .isInstanceOf(ReIdentificationDeniedException.class);
    assertThatThrownBy(() -> service.reIdentify(request(Set.of("REIDENTIFICATION"), null)))
        .isInstanceOf(ReIdentificationDeniedException.class);
  }

  @Test
  void everyAttemptIsAuditedIncludingRefusedOnes() {
    service.reIdentify(request(Set.of("REIDENTIFICATION"), "HR case 4471"));
    assertThatThrownBy(() -> service.reIdentify(request(Set.of(), "because")))
        .isInstanceOf(ReIdentificationDeniedException.class);

    assertThat(trail)
        .extracting(ReIdentificationAuditEntry::outcome)
        .containsExactly(Outcome.GRANTED, Outcome.DENIED);
    assertThat(trail.get(0).caller()).isEqualTo("auditor-1");
    assertThat(trail.get(0).pseudonym()).isEqualTo(alice);
    assertThat(trail.get(0).justification()).isEqualTo("HR case 4471");
    assertThat(trail.get(0).at()).isEqualTo(NOW);
  }

  @Test
  void theAuditEntryNeverContainsTheRevealedIdentity() {
    service.reIdentify(request(Set.of("REIDENTIFICATION"), "HR case 4471"));

    assertThat(trail.toString()).doesNotContain("alice");
  }

  @Test
  void ifTheAuditWriteFailsNothingIsDisclosed() {
    ReIdentificationService unaudited =
        new ReIdentificationService(
            directory,
            entry -> {
              throw new IllegalStateException("audit sink down");
            },
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(
            () -> unaudited.reIdentify(request(Set.of("REIDENTIFICATION"), "HR case 4471")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anUnknownPseudonymIsEmptyButStillAudited() {
    ReIdentificationRequest unknown =
        new ReIdentificationRequest(
            "auditor-1", Set.of("REIDENTIFICATION"), Pseudonym.fromStored("a".repeat(64)), "check");

    assertThat(service.reIdentify(unknown)).isEmpty();
    assertThat(trail).hasSize(1);
  }
}
