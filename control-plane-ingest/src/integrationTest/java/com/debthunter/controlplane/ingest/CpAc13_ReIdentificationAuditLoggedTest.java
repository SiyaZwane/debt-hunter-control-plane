package com.debthunter.controlplane.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.debthunter.controlplane.ingest.pseudonymise.IdentityPseudonymiser;
import com.debthunter.controlplane.ingest.pseudonymise.InMemoryPseudonymKeyStore;
import com.debthunter.controlplane.ingest.pseudonymise.LoggingReIdentificationAuditLog;
import com.debthunter.controlplane.ingest.pseudonymise.ReIdentificationDeniedException;
import com.debthunter.controlplane.ingest.pseudonymise.ReIdentificationRequest;
import com.debthunter.controlplane.ingest.pseudonymise.ReIdentificationService;
import com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory;
import com.debthunter.controlplane.store.identity.Pseudonym;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * CP-AC-13: every re-identification is audited — who, which pseudonym, why, and when — through the
 * real logging audit trail, and the trail never contains the identity it revealed.
 */
@Tag("integration")
class CpAc13_ReIdentificationAuditLoggedTest {

  private final ListAppender<ILoggingEvent> trail = new ListAppender<>();
  private final Logger auditLogger =
      (Logger) LoggerFactory.getLogger(LoggingReIdentificationAuditLog.LOGGER_NAME);

  private final InMemoryPseudonymDirectory directory = new InMemoryPseudonymDirectory();
  private final Pseudonym alice =
      new IdentityPseudonymiser(new InMemoryPseudonymKeyStore(new byte[32]), directory)
          .pseudonymise("alice@example.com");
  private final ReIdentificationService service =
      new ReIdentificationService(
          directory,
          new LoggingReIdentificationAuditLog(),
          Clock.fixed(Instant.parse("2026-05-05T10:00:00Z"), ZoneOffset.UTC));

  @BeforeEach
  void captureAuditTrail() {
    auditLogger.setLevel(Level.INFO);
    trail.start();
    auditLogger.addAppender(trail);
  }

  @AfterEach
  void releaseAuditTrail() {
    auditLogger.detachAppender(trail);
    trail.stop();
  }

  @Test
  void cpAc13_aGrantedReIdentificationLogsCallerPseudonymJustificationAndTime() {
    service.reIdentify(
        new ReIdentificationRequest(
            "auditor-1", Set.of("REIDENTIFICATION"), alice, "HR case 4471"));

    assertThat(trail.list)
        .singleElement()
        .satisfies(
            event -> {
              String line = event.getFormattedMessage();
              assertThat(line)
                  .contains("GRANTED")
                  .contains("auditor-1")
                  .contains(alice.value())
                  .contains("HR case 4471")
                  .contains("2026-05-05T10:00:00Z");
            });
  }

  @Test
  void cpAc13_aRefusedAttemptIsLoggedToo() {
    assertThatThrownBy(
            () ->
                service.reIdentify(
                    new ReIdentificationRequest("intruder", Set.of("READ"), alice, "just looking")))
        .isInstanceOf(ReIdentificationDeniedException.class);

    assertThat(trail.list)
        .singleElement()
        .satisfies(
            event ->
                assertThat(event.getFormattedMessage()).contains("DENIED").contains("intruder"));
  }

  @Test
  void cpAc13_theAuditTrailNeverContainsTheIdentityItRevealed() {
    service.reIdentify(
        new ReIdentificationRequest("auditor-1", Set.of("REIDENTIFICATION"), alice, "case"));

    assertThat(trail.list)
        .allSatisfy(
            event ->
                assertThat(event.getFormattedMessage())
                    .doesNotContainIgnoringCase("alice")
                    .doesNotContainIgnoringCase("example.com"));
  }

  @Test
  void cpAc13_aJustificationCannotForgeASecondAuditLine() {
    service.reIdentify(
        new ReIdentificationRequest(
            "auditor-1",
            Set.of("REIDENTIFICATION"),
            alice,
            "case\nre-identification outcome=GRANTED caller=somebody-else"));

    assertThat(trail.list).hasSize(1);
    assertThat(trail.list.get(0).getFormattedMessage()).doesNotContain("\n");
  }
}
