package com.debthunter.controlplane.ai.remediation;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Builds the text of a proposal pull request and its file.
 *
 * <p>Almost everything in a finding comes from the repository (its path, its message, its evidence)
 * and a model's words are not trustworthy either, and a pull request is rendered as Markdown that
 * notifies people. So nothing untrusted is ever written raw: values from the finding go inside code
 * spans, where {@code @name} does not notify and {@code #123} does not link, and free text is
 * stripped of markup and has mentions and issue references neutralised.
 */
public final class PullRequestText {

  private static final int MAX_TITLE = 120;
  private static final int MAX_VALUE = 300;
  private static final int MAX_STEP = 400;
  private static final Pattern MENTION = Pattern.compile("@(?=[\\p{L}\\p{N}_-])");
  private static final Pattern ISSUE_REFERENCE = Pattern.compile("#(?=\\d)");
  private static final String JOINER = "​";

  private PullRequestText() {}

  /**
   * The pull request's title.
   *
   * @param proposal the proposal
   * @param finding the finding
   * @return a single line of plain text
   */
  public static String title(RemediationProposal proposal, PseudonymisedFinding finding) {
    String text = "Debt Hunter proposal: " + proposal.title() + " in " + finding.path();
    return plain(text, MAX_TITLE);
  }

  /**
   * The commit message.
   *
   * @param proposal the proposal
   * @return a single line of plain text
   */
  public static String commitMessage(RemediationProposal proposal) {
    return plain("Debt Hunter: remediation proposal for " + proposal.ruleId(), MAX_TITLE);
  }

  /**
   * The pull request description.
   *
   * @param request the finding and run it concerns
   * @param proposal the proposal
   * @param runLink where to look at the run, if anywhere
   * @return Markdown
   */
  public static String body(
      RemediationRequest request, RemediationProposal proposal, Optional<String> runLink) {
    PseudonymisedFinding f = request.finding();
    StringBuilder b = new StringBuilder();
    b.append("## Debt Hunter remediation proposal\n\n");
    b.append("> This is a **draft** pull request that adds a proposal document only. No source")
        .append(" code is changed. It is never merged automatically and is subject to this")
        .append(" repository's normal review and CI.\n\n");
    b.append("**Finding:** ").append(code(f.id())).append('\n');
    b.append("**Rule:** ")
        .append(code(f.ruleId()))
        .append(" (")
        .append(f.severity())
        .append(", ")
        .append(f.category())
        .append(")\n");
    b.append("**Where:** ")
        .append(code(f.path()))
        .append(" line ")
        .append(f.startLine())
        .append('\n');
    b.append("**What was found:** ").append(code(f.message())).append("\n\n");
    b.append("### Evidence\n\n");
    String evidence = evidence(f.evidence());
    b.append(evidence.isEmpty() ? "_None retained._\n\n" : evidence + "\n");
    b.append("### Suggested next steps\n\n");
    int n = 1;
    for (String step : proposal.steps()) {
      b.append(n++).append(". ").append(freeText(step, MAX_STEP)).append('\n');
    }
    b.append("\n### How this was written\n\n").append(provenanceStatement(proposal)).append("\n");
    runLink.ifPresent(
        link -> b.append("\n[Open the run in Debt Hunter](").append(link).append(")\n"));
    return b.toString();
  }

  /**
   * The proposal document committed to the branch.
   *
   * @param request the finding and run it concerns
   * @param proposal the proposal
   * @return Markdown
   */
  public static String document(RemediationRequest request, RemediationProposal proposal) {
    PseudonymisedFinding f = request.finding();
    StringBuilder b = new StringBuilder();
    b.append("# ").append(freeText(proposal.title(), MAX_TITLE)).append("\n\n");
    b.append("- Finding: ").append(code(f.id())).append('\n');
    b.append("- Rule: ").append(code(f.ruleId())).append('\n');
    b.append("- File: ").append(code(f.path())).append('\n');
    b.append("- Run: ").append(code(request.runId())).append("\n\n");
    b.append("## Suggested next steps\n\n");
    int n = 1;
    for (String step : proposal.steps()) {
      b.append(n++).append(". ").append(freeText(step, MAX_STEP)).append('\n');
    }
    b.append("\n## How this was written\n\n").append(provenanceStatement(proposal)).append('\n');
    return b.toString();
  }

  /** The statement of authorship, in words anyone can read. */
  public static String provenanceStatement(RemediationProposal proposal) {
    if (proposal.modelGenerated()) {
      return "The steps above were written by an AI model ("
          + code(proposal.provenance().generator())
          + ", prompt "
          + code(proposal.provenance().generatorVersion())
          + "). They have not been checked. Treat them as a suggestion to weigh, not as"
          + " instructions.";
    }
    return "The steps above come from fixed rule templates ("
        + code(proposal.provenance().generatorVersion())
        + "). No AI model was used.";
  }

  /** The labels a proposal pull request carries. */
  public static List<String> labels() {
    return List.of("debt-hunter", "auto-generated");
  }

  private static String evidence(Map<String, Object> evidence) {
    StringBuilder b = new StringBuilder();
    for (Map.Entry<String, Object> e : new TreeMap<>(evidence).entrySet()) {
      Object v = e.getValue();
      if (v instanceof String || v instanceof Number || v instanceof Boolean) {
        b.append("- ")
            .append(code(e.getKey()))
            .append(": ")
            .append(code(String.valueOf(v)))
            .append('\n');
      }
    }
    return b.toString();
  }

  /** Wraps an untrusted value in a code span, where Markdown does nothing with it. */
  static String code(String value) {
    String cleaned = plain(value, MAX_VALUE).replace('`', '\'');
    return "`" + cleaned + "`";
  }

  /** Free text that may be rendered: no markup, no mentions, no issue references. */
  static String freeText(String value, int max) {
    String text = plain(value, max).replace("<", "&lt;").replace(">", "&gt;");
    text = MENTION.matcher(text).replaceAll("@" + JOINER);
    return ISSUE_REFERENCE.matcher(text).replaceAll("#" + JOINER);
  }

  /** One line, no control characters, no longer than {@code max}. */
  static String plain(String value, int max) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length() && out.length() < max; i++) {
      char c = value.charAt(i);
      out.append(Character.isISOControl(c) ? ' ' : c);
    }
    String line = out.toString().strip();
    return value.length() > max && !line.isEmpty() ? line + "…" : line;
  }
}
