package com.debthunter.controlplane.ai.rules;

import com.debthunter.domain.Category;

/**
 * What each rule the data plane raises means, in words.
 *
 * <p>The data plane's rule ids are {@code codemaat.<name>}, {@code architecture.<rule>} and {@code
 * sonar.<rule>}; the last two are open-ended, so they are described by family. Anything else is
 * described by its category, so every finding has a descriptor. The wording is deliberately modest:
 * it states what is known from the rule and does not promise outcomes.
 */
public final class RuleCatalogue {

  private RuleCatalogue() {}

  /**
   * Describes a rule.
   *
   * @param ruleId the rule's id
   * @param category the finding's category
   * @return a descriptor; never null
   */
  public static RuleDescriptor describe(String ruleId, Category category) {
    switch (ruleId) {
      case "codemaat.hotspot":
        return new RuleDescriptor(
            "Change hotspot",
            "This file is changed far more often than most files in the repository.",
            "Files that change constantly tend to collect defects and slow delivery, so they are"
                + " often where cleanup pays back soonest.",
            "Look at why this file changes so often, and whether it is doing several jobs that"
                + " could be separated.");
      case "codemaat.churn":
        return new RuleDescriptor(
            "High churn",
            "This file has been changed many times.",
            "Repeated change can indicate unclear responsibilities or code that is hard to get right.",
            "Look at the reasons for the recent changes and whether they share a cause.");
      case "codemaat.temporal-coupling":
        return new RuleDescriptor(
            "Files that change together",
            "Two files are repeatedly changed in the same commits.",
            "A hidden dependency between files makes it easy to change one and forget the other.",
            "Look for what links the two files, and whether that link should be explicit.");
      case "codemaat.knowledge-concentration":
        return new RuleDescriptor(
            "Knowledge concentrated in few authors",
            "Very few people have changed this file, so knowledge of it is concentrated.",
            "If those people are unavailable the file becomes harder to change safely.",
            "Consider pairing, review rotation or documentation for this file.");
      default:
        break;
    }
    if (ruleId.startsWith("architecture.")) {
      return new RuleDescriptor(
          "Architecture rule violation",
          "The code depends on something this repository's architecture rules do not allow.",
          "Violations erode the intended structure and make parts harder to change independently.",
          "Remove or redirect the dependency, or change the rule if the boundary is meant to move.");
    }
    if (ruleId.startsWith("sonar.")) {
      return new RuleDescriptor(
          "Static analysis issue",
          "A static analysis tool flagged this code against rule "
              + ruleId.substring("sonar.".length())
              + ".",
          "How much it matters depends on the rule and its severity; the message is the tool's own.",
          "Read the rule's documentation in the analysis tool, then fix the code or record why not.");
    }
    return new RuleDescriptor(
        pretty(category),
        "This finding was raised by rule " + ruleId + " in the " + pretty(category) + " category.",
        "How much it matters depends on the rule and its severity.",
        "Start from the message and the file it names.");
  }

  private static String pretty(Category category) {
    String lower = category.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }
}
