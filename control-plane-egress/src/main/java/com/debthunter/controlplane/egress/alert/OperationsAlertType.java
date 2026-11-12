package com.debthunter.controlplane.egress.alert;

/** The kinds of operational event the control plane raises an alert for. */
public enum OperationsAlertType {
  /** A message could not be delivered and has been set aside; someone must look at it. */
  MESSAGE_DEAD_LETTERED
}
