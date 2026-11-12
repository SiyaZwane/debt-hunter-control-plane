package com.debthunter.controlplane.egress.alert;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Collects alerts so a test can see what was raised. */
public final class RecordingAlerts implements OperationsAlertPublisher {

  public final List<OperationsAlert> alerts = new CopyOnWriteArrayList<>();

  @Override
  public void publish(OperationsAlert alert) {
    alerts.add(alert);
  }
}
