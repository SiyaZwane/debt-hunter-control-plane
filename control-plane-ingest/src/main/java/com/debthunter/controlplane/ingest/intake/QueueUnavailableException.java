package com.debthunter.controlplane.ingest.intake;

/**
 * The intake queue could not accept a bundle in the time allowed. The one failure a {@link
 * RunIntakeQueuePublisher} reports, so callers never need to know which broker is behind it.
 */
public final class QueueUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was being attempted
   * @param cause the underlying client failure
   */
  public QueueUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
