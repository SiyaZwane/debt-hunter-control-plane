package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.suppressions.Suppression;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds the words that go into a work item.
 *
 * <p>Almost everything in a finding comes from the repository (its path, its message), so nothing
 * from it is trusted: values are cut to a length, stripped of control characters, and left to the
 * tracker adapter to render safely for its own markup. No contributor identity and no source code
 * is ever included, because neither is available here: the finding carries pseudonyms only, and
 * they are not used.
 */
public final class WorkItemText {

  private static final int MAX_TITLE = 120;
  private static final int MAX_VALUE = 300;
  private static final int MAX_REASON = 400;

  /** Put after an at-sign so a mention does not notify anyone. */
  private static final String ZERO_WIDTH_SPACE = String.valueOf((char) 0x200B);

  private static final Pattern MENTION = Pattern.compile("@(?=[\\p{L}\\p{N}_-])");

  private WorkItemText() {}

  /**
   * The content of a new item.
   *
   * @param key the item's key
   * @param finding the finding
   * @param runLink where to look at the run, if anywhere
   * @return the content
   */
  public static WorkItemContent content(
      String key, PseudonymisedFinding finding, Optional<String> runLink) {
    List<WorkItemContent.Detail> details = new ArrayList<>();
    details.add(new WorkItemContent.Detail("Rule", plain(finding.ruleId(), MAX_VALUE)));
    details.add(new WorkItemContent.Detail("Severity", finding.severity().name()));
    details.add(new WorkItemContent.Detail("Category", finding.category().name()));
    details.add(
        new WorkItemContent.Detail(
            "Where", plain(finding.path(), MAX_VALUE) + " line " + finding.startLine()));
    details.add(new WorkItemContent.Detail("What was found", plain(finding.message(), MAX_VALUE)));
    String title =
        plain("Technical debt: " + finding.ruleId() + " in " + finding.path(), MAX_TITLE);
    return new WorkItemContent(key, title, details, runLink.orElse(null));
  }

  /** The comment when an item is closed. */
  public static String closed() {
    return "Closed by Debt Hunter: the finding is no longer present.";
  }

  /** The comment when an item is reopened because the finding came back. */
  public static String reopenedRegression() {
    return "Reopened by Debt Hunter: the finding has reappeared after it was resolved.";
  }

  /** The comment when an item is reopened because a suppression ended. */
  public static String reopenedSuppressionEnded() {
    return "Reopened by Debt Hunter: the suppression has expired and the finding is still"
        + " present.";
  }

  /** The comment when an item is reopened for any other reason. */
  public static String reopened() {
    return "Reopened by Debt Hunter: the finding is open.";
  }

  /**
   * The comment when an item is put on hold.
   *
   * @param suppression the suppression, if it can be found
   * @return the comment, with the suppression's reason and end date
   */
  public static String onHold(Optional<Suppression> suppression) {
    if (suppression.isEmpty()) {
      return "Put on hold by Debt Hunter: the finding has been suppressed.";
    }
    Suppression s = suppression.get();
    Instant expires = s.expiresAt();
    return "Put on hold by Debt Hunter: the finding has been suppressed. Reason: "
        + plain(s.reason(), MAX_REASON)
        + ". The suppression ends on "
        + expires
        + "; if the finding is still present after that, this item is reopened.";
  }

  /** One line, no control characters, no mentions, no longer than {@code max}. */
  static String plain(String value, int max) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length() && out.length() < max; i++) {
      char c = value.charAt(i);
      out.append(Character.isISOControl(c) ? ' ' : c);
    }
    String line = out.toString().strip();
    line = MENTION.matcher(line).replaceAll("@" + ZERO_WIDTH_SPACE);
    return value.length() > max && !line.isEmpty() ? line + "…" : line;
  }
}
