package com.debthunter.controlplane.egress.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes each alert to the application log at {@code ERROR} on a dedicated logger, {@value
 * #LOGGER_NAME}, so it can be routed or alerted on separately. The baseline implementation until a
 * notification channel exists.
 *
 * <p>The key and reason can contain text that came from outside, so control characters are removed
 * before logging; otherwise they would let a caller forge log lines.
 */
public final class LoggingOperationsAlertPublisher implements OperationsAlertPublisher {

  /** The logger every alert is written to. */
  public static final String LOGGER_NAME = "com.debthunter.controlplane.operations.alerts";

  private static final int MAX_FIELD_LENGTH = 200;

  private final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

  @Override
  public void publish(OperationsAlert alert) {
    try {
      log.error(
          "OPERATIONS ALERT type={} kind={} key={} item={} reason={} occurredAt={}",
          alert.type(),
          sanitise(alert.kind()),
          sanitise(alert.key()),
          alert.itemId(),
          sanitise(alert.reason()),
          alert.occurredAt());
    } catch (RuntimeException e) {
      // Raising an alert must never disturb the work that triggered it.
    }
  }

  private static String sanitise(String value) {
    String cleaned = value.replaceAll("\\p{Cntrl}", "_");
    return cleaned.length() <= MAX_FIELD_LENGTH ? cleaned : cleaned.substring(0, MAX_FIELD_LENGTH);
  }
}
