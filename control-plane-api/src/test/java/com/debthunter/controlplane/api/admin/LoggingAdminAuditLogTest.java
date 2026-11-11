package com.debthunter.controlplane.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingAdminAuditLogTest {

  private final ListAppender<ILoggingEvent> trail = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingAdminAuditLog.LOGGER_NAME);

  @BeforeEach
  void capture() {
    logger.setLevel(Level.INFO);
    trail.start();
    logger.addAppender(trail);
  }

  @AfterEach
  void release() {
    logger.detachAppender(trail);
    trail.stop();
  }

  private static AdminAuditEntry entry(String caller, String target) {
    return new AdminAuditEntry(
        caller, "GRANT_WORKLOAD", target, Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void aLineCarriesWhoWhatToWhatAndWhen() {
    new LoggingAdminAuditLog().record(entry("admin-alice", "workload=svc repository=acme/widgets"));

    assertThat(trail.list)
        .singleElement()
        .satisfies(
            e ->
                assertThat(e.getFormattedMessage())
                    .contains("GRANT_WORKLOAD")
                    .contains("admin-alice")
                    .contains("workload=svc repository=acme/widgets")
                    .contains("2026-01-01T00:00:00Z"));
  }

  @Test
  void supplyingNewlinesCannotForgeASecondAuditLine() {
    new LoggingAdminAuditLog()
        .record(
            entry("admin\nadmin action=OFFBOARD_REPOSITORY caller=someone-else", "repo\r\nfake"));

    assertThat(trail.list).hasSize(1);
    assertThat(trail.list.get(0).getFormattedMessage()).doesNotContain("\n").doesNotContain("\r");
  }

  @Test
  void anAbsurdlyLongValueIsTruncated() {
    new LoggingAdminAuditLog().record(entry("a", "x".repeat(5000)));

    assertThat(trail.list.get(0).getFormattedMessage().length()).isLessThan(800);
  }
}
