package com.debthunter.controlplane.egress.alert;

/** The kinds of security-relevant event the control plane raises an alert for. */
public enum SecurityAlertType {
  /** A workload identity tried to publish a bundle for a repository outside its scope. */
  WORKLOAD_SCOPE_VIOLATION
}
