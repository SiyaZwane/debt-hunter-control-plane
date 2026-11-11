package com.debthunter.controlplane.api.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the administration audit trail to the application log under a dedicated logger name
 * ({@value #LOGGER_NAME}), so it can be routed to its own append-only sink.
 */
public final class LoggingAdminAuditLog implements AdminAuditLog {

  /** The logger the trail is written to. */
  public static final String LOGGER_NAME = "com.debthunter.controlplane.audit.admin";

  private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

  @Override
  public void record(AdminAuditEntry entry) {
    log.info(
        "admin action={} caller={} target={} at={}",
        clean(entry.action()),
        clean(entry.caller()),
        clean(entry.target()),
        entry.at());
  }

  /** Request-supplied text must not be able to forge a second log line. */
  private static String clean(String text) {
    String cleaned = text.replaceAll("\\p{Cntrl}", " ");
    return cleaned.length() <= 300 ? cleaned : cleaned.substring(0, 300) + "…";
  }
}
