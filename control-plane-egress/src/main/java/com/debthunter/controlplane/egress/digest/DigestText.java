package com.debthunter.controlplane.egress.digest;

import com.debthunter.domain.Severity;
import java.util.regex.Pattern;

/**
 * Turns a digest into the text a chat channel shows.
 *
 * <p>Nearly everything in a digest came from a repository (a path, a message, a reason) and a chat
 * message can notify people and render links, so no such value is written raw. Each is cut to a
 * length, stripped of control characters, has its mentions broken, has the characters that start
 * markup or a mention escaped, and is put in a code span, where the channel does nothing with it.
 */
public final class DigestText {

  /** The chat product the text is for. */
  public enum Style {
    /** Slack's mrkdwn. */
    SLACK("*", "\n"),
    /** Teams markdown. */
    TEAMS("**", "\n\n");

    private final String bold;
    private final String lineBreak;

    Style(String bold, String lineBreak) {
      this.bold = bold;
      this.lineBreak = lineBreak;
    }
  }

  private static final String ZERO_WIDTH_SPACE = String.valueOf((char) 0x200B);
  private static final Pattern MENTION = Pattern.compile("@(?=[\\p{L}\\p{N}_-])");

  private DigestText() {}

  /** One line, no control characters, mentions broken, no longer than {@code max}. */
  static String clean(String value, int max) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length() && out.length() < max; i++) {
      char c = value.charAt(i);
      out.append(Character.isISOControl(c) ? ' ' : c);
    }
    String line = out.toString().strip();
    line = MENTION.matcher(line).replaceAll("@" + ZERO_WIDTH_SPACE);
    return value.length() > max && !line.isEmpty() ? line + "…" : line;
  }

  /**
   * Renders a digest.
   *
   * @param m the digest
   * @param style the chat product
   * @return text for the channel
   */
  public static String render(DigestMessage m, Style style) {
    String b = style.bold;
    String nl = style.lineBreak;
    StringBuilder t = new StringBuilder();
    t.append(b)
        .append("Debt Hunter digest for ")
        .append(untrusted(m.squad()))
        .append(b)
        .append(" (")
        .append(m.periodStart())
        .append(" to ")
        .append(m.periodEnd())
        .append(')')
        .append(nl);
    t.append("New findings: ").append(counts(m.newBySeverity())).append(nl);
    t.append("Resolved: ").append(m.resolved()).append(", regressed: ").append(m.regressed());
    t.append(nl).append("Open now: ").append(counts(m.openBySeverity())).append(nl);
    if (!m.expiring().isEmpty()) {
      t.append(b).append("Suppressions ending soon").append(b).append(nl);
      for (DigestMessage.Expiring e : m.expiring()) {
        t.append("• ")
            .append(untrusted(e.repository()))
            .append(" ends ")
            .append(e.endsOn())
            .append(": ")
            .append(untrusted(e.reason()))
            .append(nl);
      }
    }
    if (m.distinctFiles() > 0) {
      t.append("Files with a new or returning finding: ").append(m.distinctFiles()).append(nl);
    }
    if (!m.notable().isEmpty()) {
      t.append(b).append("Worth a look").append(b).append(nl);
      for (DigestMessage.Notable n : m.notable()) {
        t.append("• ")
            .append(n.severity())
            .append(' ')
            .append(untrusted(n.rule()))
            .append(" in ")
            .append(untrusted(n.path()))
            .append(": ")
            .append(untrusted(n.message()))
            .append(n.regression() ? " (came back)" : "")
            .append(nl);
      }
      if (m.notableOmitted() > 0) {
        t.append("…and ").append(m.notableOmitted()).append(" more").append(nl);
      }
    }
    if (!m.stages().isEmpty()) {
      t.append(b).append("Rollout stages").append(b).append(nl);
      for (DigestMessage.StageLine s : m.stages()) {
        t.append("• ").append(untrusted(s.repository())).append(" ").append(s.stage()).append(nl);
      }
      if (m.stagesOmitted() > 0) {
        t.append("…and ").append(m.stagesOmitted()).append(" more repositories").append(nl);
      }
    }
    if (m.link() != null) {
      t.append("Details: ").append(m.link()).append(nl);
    }
    t.append("For full detail, re-run the Debt Hunter scan on the repository.");
    return t.toString();
  }

  private static String counts(java.util.Map<String, Integer> byName) {
    if (byName.isEmpty()) {
      return "none";
    }
    int total = byName.values().stream().mapToInt(Integer::intValue).sum();
    StringBuilder out = new StringBuilder().append(total).append(" (");
    boolean first = true;
    for (Severity s : Severity.values()) {
      Integer n = byName.get(s.name());
      if (n != null) {
        out.append(first ? "" : ", ").append(s.name()).append(' ').append(n);
        first = false;
      }
    }
    return out.append(')').toString();
  }

  /** An untrusted value, escaped and put in a code span. */
  static String untrusted(String value) {
    String escaped =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('`', '\'');
    return "`" + escaped + "`";
  }
}
