package com.debthunter.controlplane.governance.policy;

import java.util.ArrayList;
import java.util.List;

/** Builds policy bundle text for tests, so each test says only what it is about. */
public final class PolicyYaml {

  private final String version;
  private final List<String> mainRules = new ArrayList<>();
  private final List<String> pullRequestRules = new ArrayList<>();
  private final List<String> excludedCategories = new ArrayList<>();
  private final List<String> excludedPaths = new ArrayList<>();
  private String minimumHistoryDepth;

  private PolicyYaml(String version) {
    this.version = version;
  }

  public static PolicyYaml policy(String version) {
    return new PolicyYaml(version);
  }

  public PolicyYaml main(String id, String severity, int maxCount) {
    mainRules.add(rule(id, severity, maxCount));
    return this;
  }

  public PolicyYaml pullRequest(String id, String severity, int maxCount) {
    pullRequestRules.add(rule(id, severity, maxCount));
    return this;
  }

  public PolicyYaml excludeCategory(String category) {
    excludedCategories.add(category);
    return this;
  }

  public PolicyYaml excludePath(String path) {
    excludedPaths.add(path);
    return this;
  }

  public PolicyYaml requireHistory(String depth) {
    this.minimumHistoryDepth = depth;
    return this;
  }

  private static String rule(String id, String severity, int maxCount) {
    return "      - id: "
        + id
        + "\n        severity: "
        + severity
        + "\n        maxCount: "
        + maxCount
        + "\n";
  }

  public String text() {
    StringBuilder sb = new StringBuilder("version: \"" + version + "\"\n");
    if (minimumHistoryDepth != null) {
      sb.append("analysis:\n  minimumHistoryDepth: ").append(minimumHistoryDepth).append('\n');
    }
    if (!mainRules.isEmpty() || !pullRequestRules.isEmpty()) {
      sb.append("policy:\n");
      if (!mainRules.isEmpty()) {
        sb.append("  main:\n    rules:\n");
        mainRules.forEach(sb::append);
      }
      if (!pullRequestRules.isEmpty()) {
        sb.append("  pullRequest:\n    rules:\n");
        pullRequestRules.forEach(sb::append);
      }
    }
    if (!excludedCategories.isEmpty() || !excludedPaths.isEmpty()) {
      sb.append("exclusions:\n");
      if (!excludedCategories.isEmpty()) {
        sb.append("  categories:\n");
        excludedCategories.forEach(c -> sb.append("    - ").append(c).append('\n'));
      }
      if (!excludedPaths.isEmpty()) {
        sb.append("  paths:\n");
        excludedPaths.forEach(p -> sb.append("    - \"").append(p).append("\"\n"));
      }
    }
    return sb.toString();
  }
}
