package com.debthunter.controlplane.egress.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingSecurityAlertPublisherTest {

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Logger logger =
      (Logger) LoggerFactory.getLogger(LoggingSecurityAlertPublisher.LOGGER_NAME);
  private final SecurityAlertPublisher publisher = new LoggingSecurityAlertPublisher();

  @BeforeEach
  void attach() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
  }

  @Test
  void anAlertIsLoggedAtWarnWithItsFacts() {
    publisher.publish(alert("svc-a", "acme/widgets"));

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .contains("WORKLOAD_SCOPE_VIOLATION")
        .contains("svc-a")
        .contains("acme/widgets");
  }

  @Test
  void controlCharactersInUntrustedFieldsCannotForgeLogLines() {
    publisher.publish(alert("svc-a", "acme/widgets\nSECURITY ALERT type=FORGED"));

    String message = appender.list.get(0).getFormattedMessage();
    assertThat(message).doesNotContain("\n").doesNotContain("\r");
  }

  @Test
  void anOverlongFieldIsTruncated() {
    publisher.publish(alert("svc-a", "r".repeat(5_000)));

    assertThat(appender.list.get(0).getFormattedMessage().length()).isLessThan(1_000);
  }

  @Test
  void publishingNeverThrows() {
    assertThatCode(() -> publisher.publish(null)).doesNotThrowAnyException();
  }

  private static SecurityAlert alert(String identity, String repository) {
    return new SecurityAlert(
        SecurityAlertType.WORKLOAD_SCOPE_VIOLATION,
        identity,
        repository,
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
