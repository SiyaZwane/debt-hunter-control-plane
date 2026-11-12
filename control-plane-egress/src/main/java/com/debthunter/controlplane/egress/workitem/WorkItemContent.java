package com.debthunter.controlplane.egress.workitem;

import java.util.List;
import java.util.Objects;

/**
 * What a new work item says, independent of any tracker's markup. Every value is untrusted text
 * (most of it came from the repository), so an adapter must render each one safely for its tracker;
 * labels are the control plane's own.
 *
 * @param key the item's key, which the adapter records on the item so the item can be found again
 * @param title a single line
 * @param details labelled facts about the finding
 * @param runLink where to look at the run, or {@code null}
 */
public record WorkItemContent(String key, String title, List<Detail> details, String runLink) {

  /**
   * One labelled fact.
   *
   * @param label what it is
   * @param value its value, untrusted
   */
  public record Detail(String label, String value) {

    /** Validates the components. */
    public Detail {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(value, "value");
    }
  }

  /** Validates and copies the components. */
  public WorkItemContent {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(title, "title");
    details = List.copyOf(details);
  }
}
