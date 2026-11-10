package com.debthunter.controlplane.api.ingestion;

import com.debthunter.controlplane.egress.alert.SecurityAlert;
import com.debthunter.controlplane.egress.alert.SecurityAlertPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An alert publisher double that records alerts and can be told to misbehave. */
public final class RecordingAlerts implements SecurityAlertPublisher {

  private final List<SecurityAlert> alerts = new CopyOnWriteArrayList<>();
  private volatile boolean throwing;

  /**
   * Makes later publishes throw, to prove callers do not depend on alerting succeeding.
   *
   * @param throwing whether to throw
   */
  public void setThrowing(boolean throwing) {
    this.throwing = throwing;
  }

  /**
   * What has been raised.
   *
   * @return the alerts so far, in order
   */
  public List<SecurityAlert> alerts() {
    return alerts;
  }

  @Override
  public void publish(SecurityAlert alert) {
    if (throwing) {
      throw new IllegalStateException("alerting is down");
    }
    alerts.add(alert);
  }
}
