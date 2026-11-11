package com.debthunter.controlplane.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a schema-v1 run bundle for a scenario: which run, which commit, which findings were
 * observed, and whether the run was complete. Used to drive the finding lifecycle.
 */
public final class RunBundleBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private String runId = "run-1";
  private String repository = RunBundleFixtures.DEFAULT_REPOSITORY;
  private String commit = "c1";
  private String project;
  private String toolVersion = "0.1.0";
  private String timestamp = "2026-01-01T00:00:00Z";
  private boolean degraded;
  private String baseCommit;
  private String pullRequest;
  private final List<String> fingerprints = new ArrayList<>();
  private final List<String> authors = new ArrayList<>();

  private RunBundleBuilder() {}

  /**
   * Starts a bundle.
   *
   * @return a builder with defaults
   */
  public static RunBundleBuilder run() {
    return new RunBundleBuilder();
  }

  /**
   * Sets the run id.
   *
   * @param id the run id
   * @return this
   */
  public RunBundleBuilder id(String id) {
    this.runId = id;
    return this;
  }

  /**
   * Sets the repository.
   *
   * @param value the repository
   * @return this
   */
  public RunBundleBuilder repository(String value) {
    this.repository = value;
    return this;
  }

  /**
   * Sets the commit analysed.
   *
   * @param value the commit id
   * @return this
   */
  public RunBundleBuilder commit(String value) {
    this.commit = value;
    return this;
  }

  /**
   * Sets the project slice.
   *
   * @param value the project name
   * @return this
   */
  public RunBundleBuilder project(String value) {
    this.project = value;
    return this;
  }

  /**
   * Sets the tool version.
   *
   * @param value the version
   * @return this
   */
  public RunBundleBuilder toolVersion(String value) {
    this.toolVersion = value;
    return this;
  }

  /**
   * Sets the run timestamp.
   *
   * @param value an ISO-8601 instant
   * @return this
   */
  public RunBundleBuilder timestamp(String value) {
    this.timestamp = value;
    return this;
  }

  /**
   * Marks the run degraded: one engine failed, so absence proves nothing.
   *
   * @return this
   */
  public RunBundleBuilder degraded() {
    this.degraded = true;
    return this;
  }

  /**
   * Marks the run as a pull-request diff, covering only part of the code.
   *
   * @param base the base commit it was diffed against
   * @param pr the pull request id
   * @return this
   */
  public RunBundleBuilder pullRequest(String base, String pr) {
    this.baseCommit = base;
    this.pullRequest = pr;
    return this;
  }

  /**
   * Adds findings the run observed.
   *
   * @param values fingerprints
   * @return this
   */
  public RunBundleBuilder observing(String... values) {
    fingerprints.addAll(List.of(values));
    return this;
  }

  /**
   * Names contributors on every finding.
   *
   * @param values contributor identities
   * @return this
   */
  public RunBundleBuilder authoredBy(String... values) {
    authors.addAll(List.of(values));
    return this;
  }

  /**
   * Produces the bundle.
   *
   * @return its JSON text
   */
  public String build() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schemaVersion", "1.0");
    ObjectNode run = root.putObject("run");
    run.put("id", runId);
    run.put("toolVersion", toolVersion);
    run.putNull("imageDigest");
    run.put("timestamp", timestamp);
    run.put("repository", repository);
    if (project == null) {
      run.putNull("project");
    } else {
      run.put("project", project);
    }
    run.put("commit", commit);
    if (baseCommit == null) {
      run.putNull("baseCommit");
    } else {
      run.put("baseCommit", baseCommit);
    }
    run.put("branch", "main");
    if (pullRequest == null) {
      run.putNull("pullRequest");
    } else {
      run.put("pullRequest", pullRequest);
    }
    run.put("historyDepth", "FULL");
    ArrayNode engines = run.putArray("engines");
    ObjectNode engine = engines.addObject();
    engine.put("id", "code-maat");
    engine.put("version", "1.0.0");
    engine.put("status", degraded ? "FAILED" : "OK");
    engine.put("durationMs", 10);
    if (degraded) {
      engine.put("reason", "engine crashed");
    } else {
      engine.putNull("reason");
    }
    run.put("degraded", degraded);
    run.put("baselineProvenance", "NONE");

    ArrayNode findings = root.putArray("findings");
    for (String fingerprint : fingerprints) {
      ObjectNode finding = findings.addObject();
      finding.put("id", "finding-" + fingerprint);
      finding.put("ruleId", "codemaat.hotspot");
      finding.put("category", "HOTSPOT");
      finding.put("severity", "HIGH");
      finding.put("confidence", 0.9);
      finding.put("path", "src/" + fingerprint + ".java");
      finding.put("startLine", 0);
      finding.put("message", fingerprint + " is a change hotspot");
      ObjectNode evidence = finding.putObject("evidence");
      evidence.put("changeFrequency", 12.0);
      if (!authors.isEmpty()) {
        ArrayNode list = evidence.putArray("authors");
        authors.forEach(list::add);
      }
      finding.put("score", 12.0);
      finding.put("isNew", true);
      finding.put("fingerprint", fingerprint);
    }
    root.putObject("metrics");
    ObjectNode policy = root.putObject("policy");
    policy.put("bundleVersion", "1.0");
    policy.put("status", "PASSED");
    policy.putArray("reasons");
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
