package com.debthunter.controlplane.egress.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes each alert to the application log at {@code WARN} on a dedicated logger, {@value
 * #LOGGER_NAME}, so it can be routed or alerted on separately. The baseline implementation until a
 * notification channel exists.
 *
 * <p>The workload identity and declared repository come from the request, so they are sanitised
 * before logging: control characters would otherwise let a caller forge log lines.
 */
public final class LoggingSecurityAlertPublisher implements SecurityAlertPublisher {

  /** The logger every alert is written to. */
  public static final String LOGGER_NAME = "com.debthunter.controlplane.security.alerts";

  private static final int MAX_FIELD_LENGTH = 200;

  private final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

  @Override
  public void publish(SecurityAlert alert) {
    try {
      log.warn(
          "SECURITY ALERT type={} workloadIdentity={} declaredRepository={} occurredAt={}",
          alert.type(),
          sanitise(alert.workloadIdentity()),
          sanitise(alert.declaredRepository()),
          alert.occurredAt());
    } catch (RuntimeException e) {
      // Raising an alert must never disturb the request that triggered it.
    }
  }

  private static String sanitise(String value) {
    String cleaned = value.replaceAll("\\p{Cntrl}", "_");
    return cleaned.length() <= MAX_FIELD_LENGTH ? cleaned : cleaned.substring(0, MAX_FIELD_LENGTH);
  }
}
