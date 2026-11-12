package com.debthunter.controlplane.egress.workitem;

/**
 * Makes untrusted text safe for a tracker's markup. A value from a repository must never become
 * markup, a mention that notifies someone, or a link.
 */
final class Rendering {

  private Rendering() {}

  /** For HTML: every character that could start markup is escaped. */
  static String html(String value) {
    StringBuilder out = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * For Jira wiki markup: the characters that start macros, links, mentions, tables and images are
   * removed, so what is left is plain text.
   */
  static String wiki(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if ("{}[]|!".indexOf(c) < 0) {
        out.append(c);
      }
    }
    String cleaned = out.toString().strip();
    return cleaned.isEmpty() ? "-" : cleaned;
  }
}
