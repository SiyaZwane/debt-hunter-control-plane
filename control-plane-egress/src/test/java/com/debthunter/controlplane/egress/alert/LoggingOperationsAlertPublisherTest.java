package com.debthunter.controlplane.egress.alert;

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

class LoggingOperationsAlertPublisherTest {

  private final Logger logger =
      (Logger) LoggerFactory.getLogger(LoggingOperationsAlertPublisher.LOGGER_NAME);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

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
  void writesOneErrorLinePerAlert() {
    new LoggingOperationsAlertPublisher()
        .publish(
            new OperationsAlert(
                OperationsAlertType.MESSAGE_DEAD_LETTERED,
                "work-item-sync",
                "acme|root|fp",
                7,
                "gave up",
                Instant.parse("2026-07-01T00:00:00Z")));

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("MESSAGE_DEAD_LETTERED", "work-item-sync", "acme|root|fp", "item=7", "gave up");
  }

  @Test
  void textFromOutsideCannotForgeALogLine() {
    new LoggingOperationsAlertPublisher()
        .publish(
            new OperationsAlert(
                OperationsAlertType.MESSAGE_DEAD_LETTERED,
                "kind",
                "key\nFAKE LINE",
                1,
                "reason\r\nFORGED",
                Instant.now()));

    assertThat(appender.list.get(0).getFormattedMessage())
        .doesNotContain("\n")
        .doesNotContain("\r");
  }

  @Test
  void aLongReasonIsCut() {
    new LoggingOperationsAlertPublisher()
        .publish(
            new OperationsAlert(
                OperationsAlertType.MESSAGE_DEAD_LETTERED,
                "k",
                "k",
                1,
                "x".repeat(5000),
                Instant.now()));

    assertThat(appender.list.get(0).getFormattedMessage()).hasSizeLessThan(600);
  }

  @Test
  void raisingAnAlertNeverThrows() {
    LoggingOperationsAlertPublisher publisher = new LoggingOperationsAlertPublisher();
    logger.detachAppender(appender);
    org.assertj.core.api.Assertions.assertThatCode(
            () ->
                publisher.publish(
                    new OperationsAlert(
                        OperationsAlertType.MESSAGE_DEAD_LETTERED,
                        "k",
                        "k",
                        1,
                        "r",
                        Instant.now())))
        .doesNotThrowAnyException();
  }
}
