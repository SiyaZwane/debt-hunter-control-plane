package com.debthunter.controlplane.egress.alert;

import java.time.Instant;
import java.util.Objects;

/**
 * A security-relevant event, with only the facts needed to investigate it.
 *
 * @param type what happened
 * @param workloadIdentity the authenticated caller involved
 * @param declaredRepository the repository the caller claimed — untrusted input, as supplied
 * @param occurredAt when it happened
 */
public record SecurityAlert(
    SecurityAlertType type,
    String workloadIdentity,
    String declaredRepository,
    Instant occurredAt) {

  /** Validates that every component is present. */
  public SecurityAlert {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(workloadIdentity, "workloadIdentity");
    Objects.requireNonNull(declaredRepository, "declaredRepository");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
