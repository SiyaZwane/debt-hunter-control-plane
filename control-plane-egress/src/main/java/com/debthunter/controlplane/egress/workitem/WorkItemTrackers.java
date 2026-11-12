package com.debthunter.controlplane.egress.workitem;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** The trackers this deployment can talk to, by the name a route gives. */
public final class WorkItemTrackers {

  private final Map<String, WorkItemTracker> byName = new TreeMap<>();

  /**
   * Creates the set.
   *
   * @param trackers tracker by name, such as {@code jira} or {@code azure-boards}; names match
   *     without regard to case
   */
  public WorkItemTrackers(Map<String, WorkItemTracker> trackers) {
    trackers.forEach((name, tracker) -> byName.put(name.toLowerCase(Locale.ROOT), tracker));
  }

  /**
   * The tracker a route names.
   *
   * @param name the tracker name from a route
   * @return the tracker, or empty if this deployment has none by that name
   */
  public Optional<WorkItemTracker> named(String name) {
    return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
  }
}
