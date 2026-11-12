package com.debthunter.controlplane.egress.alert;

import java.time.Instant;
import java.util.Objects;

/**
 * Something an operator should look at. It names the work and why, never what the work contained.
 *
 * @param type what happened
 * @param kind the kind of work (for example the work-item queue)
 * @param key the ordering key of the work, which identifies the finding or repository concerned
 * @param itemId the queue item to inspect
 * @param reason why, in a sentence; never contains a payload or a credential
 * @param occurredAt when
 */
public record OperationsAlert(
    OperationsAlertType type,
    String kind,
    String key,
    long itemId,
    String reason,
    Instant occurredAt) {

  /** Validates that every component is present. */
  public OperationsAlert {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
