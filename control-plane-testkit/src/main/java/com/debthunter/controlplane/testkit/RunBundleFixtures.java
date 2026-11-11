package com.debthunter.controlplane.testkit;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Factory methods producing run bundles — the JSON a pipeline publishes after a scan — at each
 * supported schema version, plus targeted corruptions of them for the negative-path tests.
 *
 * <p>The v1 shape follows the data plane's real {@code debt-hunter.json}. The v0 (N-1) shape is
 * provisional: it is the v1 shape with the fields v1 added left out, and will be aligned with the
 * normaliser's field mapping when that is built.
 */
public final class RunBundleFixtures {

  /** The repository identifier the default fixtures declare. */
  public static final String DEFAULT_REPOSITORY = "acme/widgets";

  /** The run id the default fixtures carry. */
  public static final String DEFAULT_RUN_ID = "run-0001";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RunBundleFixtures() {}

  /**
   * A valid schema-v1 bundle with the default run id and repository.
   *
   * @return the bundle's JSON text
   */
  public static String validV1() {
    return validV1(DEFAULT_RUN_ID, DEFAULT_REPOSITORY);
  }

  /**
   * A valid schema-v1 bundle.
   *
   * @param runId the run id
   * @param repository the repository the bundle declares
   * @return the bundle's JSON text
   */
  public static String validV1(String runId, String repository) {
    return validV1WithFindings(runId, repository, 1);
  }

  /**
   * A valid schema-v1 bundle carrying a given number of findings.
   *
   * @param runId the run id
   * @param repository the repository the bundle declares
   * @param findingCount how many findings to include
   * @return the bundle's JSON text
   */
  public static String validV1WithFindings(String runId, String repository, int findingCount) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schemaVersion", "1.0");

    ObjectNode run = root.putObject("run");
    run.put("id", runId);
    run.put("toolVersion", "0.1.0");
    run.putNull("imageDigest");
    run.put("timestamp", "2026-01-01T00:00:00Z");
    run.put("repository", repository);
    run.putNull("project");
    run.put("commit", "abc123");
    run.putNull("baseCommit");
    run.put("branch", "main");
    run.putNull("pullRequest");
    run.put("historyDepth", "FULL");
    ObjectNode engine = run.putArray("engines").addObject();
    engine.put("id", "code-maat");
    engine.put("version", "1.0.0");
    engine.put("status", "OK");
    engine.put("durationMs", 120);
    engine.putNull("reason");
    run.put("degraded", false);
    run.put("baselineProvenance", "NONE");

    ArrayNode findings = root.putArray("findings");
    for (int i = 0; i < findingCount; i++) {
      ObjectNode finding = findings.addObject();
      finding.put("id", "finding-" + i);
      finding.put("ruleId", "codemaat.hotspot");
      finding.put("category", "HOTSPOT");
      finding.put("severity", "HIGH");
      finding.put("confidence", 0.9);
      finding.put("path", "src/Foo" + i + ".java");
      finding.put("startLine", 0);
      finding.put("message", "src/Foo" + i + ".java is a change hotspot");
      finding.putObject("evidence").put("changeFrequency", 12.0);
      finding.put("score", 12.0);
      finding.put("isNew", true);
      finding.put("fingerprint", "fp-" + i);
    }

    root.putObject("metrics");
    ObjectNode policy = root.putObject("policy");
    policy.put("bundleVersion", "1.0");
    policy.put("status", "PASSED");
    policy.putArray("reasons");
    return write(root);
  }

  /**
   * A valid schema-v0 (N-1) bundle with the default run id and repository.
   *
   * @return the bundle's JSON text
   */
  public static String validV0() {
    return validV0(DEFAULT_RUN_ID, DEFAULT_REPOSITORY);
  }

  /**
   * A valid schema-v0 (N-1) bundle: the v1 shape without the fields v1 added.
   *
   * @param runId the run id
   * @param repository the repository the bundle declares
   * @return the bundle's JSON text
   */
  public static String validV0(String runId, String repository) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("schemaVersion", "0.9");

    ObjectNode run = root.putObject("run");
    run.put("id", runId);
    run.put("toolVersion", "0.0.9");
    run.put("timestamp", "2025-06-01T00:00:00Z");
    run.put("repository", repository);
    run.put("commit", "abc123");
    run.put("historyDepth", "FULL");
    ObjectNode engine = run.putArray("engines").addObject();
    engine.put("id", "code-maat");
    engine.put("version", "1.0.0");
    engine.put("status", "OK");
    engine.put("durationMs", 120);

    ObjectNode finding = root.putArray("findings").addObject();
    finding.put("id", "finding-0");
    finding.put("ruleId", "codemaat.hotspot");
    finding.put("category", "HOTSPOT");
    finding.put("severity", "HIGH");
    finding.put("path", "src/Foo0.java");
    finding.put("startLine", 0);
    finding.put("message", "src/Foo0.java is a change hotspot");
    finding.put("fingerprint", "fp-0");

    ObjectNode policy = root.putObject("policy");
    policy.put("status", "PASSED");
    policy.putArray("reasons");
    return write(root);
  }

  /**
   * A sanitised copy of a report the real data plane produced, kept as a golden to guard against
   * the two repositories' idea of the bundle shape drifting apart.
   *
   * @return the report's JSON text
   */
  public static String realDataPlaneReport() {
    try (InputStream in =
        RunBundleFixtures.class.getResourceAsStream("/fixtures/real-data-plane-report-v1.json")) {
      if (in == null) {
        throw new IllegalStateException("fixtures/real-data-plane-report-v1.json is missing");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Text that is not JSON at all.
   *
   * @return the malformed text
   */
  public static String notJson() {
    return "{ this is not json";
  }

  /**
   * Replaces the bundle's {@code schemaVersion}.
   *
   * @param json the bundle's JSON text
   * @param schemaVersion the version to declare
   * @return the modified bundle
   */
  public static String withSchemaVersion(String json, String schemaVersion) {
    return withValueAt(json, "/schemaVersion", MAPPER.getNodeFactory().textNode(schemaVersion));
  }

  /**
   * Removes the field a JSON pointer addresses.
   *
   * @param json the bundle's JSON text
   * @param pointer a JSON pointer such as {@code /run/repository}
   * @return the modified bundle
   */
  public static String withoutField(String json, String pointer) {
    ObjectNode root = parse(json);
    JsonPointer target = JsonPointer.compile(pointer);
    JsonNode parent = root.at(target.head());
    if (!(parent instanceof ObjectNode parentObject)) {
      throw new IllegalArgumentException("No object at the parent of " + pointer);
    }
    parentObject.remove(target.last().getMatchingProperty());
    return write(root);
  }

  /**
   * Sets the value a JSON pointer addresses, creating no intermediate objects.
   *
   * @param json the bundle's JSON text
   * @param pointer a JSON pointer such as {@code /run/id}
   * @param value the value to set
   * @return the modified bundle
   */
  public static String withValueAt(String json, String pointer, JsonNode value) {
    ObjectNode root = parse(json);
    JsonPointer target = JsonPointer.compile(pointer);
    JsonNode parent = root.at(target.head());
    if (!(parent instanceof ObjectNode parentObject)) {
      throw new IllegalArgumentException("No object at the parent of " + pointer);
    }
    parentObject.set(target.last().getMatchingProperty(), value);
    return write(root);
  }

  private static ObjectNode parse(String json) {
    try {
      return (ObjectNode) MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Fixture is not valid JSON", e);
    }
  }

  private static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
