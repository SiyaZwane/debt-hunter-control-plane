package com.debthunter.controlplane.ingest.pseudonymise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the re-identification audit trail to the application log under a dedicated logger name
 * ({@value #LOGGER_NAME}), so it can be routed to its own append-only sink.
 */
public final class LoggingReIdentificationAuditLog implements ReIdentificationAuditLog {

  /** The logger the trail is written to. */
  public static final String LOGGER_NAME = "com.debthunter.controlplane.audit.reidentification";

  private static final int MAX_JUSTIFICATION = 500;

  private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

  @Override
  public void record(ReIdentificationAuditEntry entry) {
    log.info(
        "re-identification outcome={} caller={} pseudonym={} at={} justification={}",
        entry.outcome(),
        clean(entry.caller(), 200),
        entry.pseudonym(),
        entry.at(),
        clean(entry.justification(), MAX_JUSTIFICATION));
  }

  /** Caller-supplied text must not be able to forge a second log line. */
  private static String clean(String text, int max) {
    String cleaned = text.replaceAll("\\p{Cntrl}", " ");
    return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "…";
  }
}
