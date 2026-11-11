package com.debthunter.controlplane.ingest.normalise;

import com.debthunter.domain.AnalysisRun;
import com.debthunter.domain.Category;
import com.debthunter.domain.DebtMetric;
import com.debthunter.domain.EngineHealth;
import com.debthunter.domain.EngineStatus;
import com.debthunter.domain.Finding;
import com.debthunter.domain.HistoryDepth;
import com.debthunter.domain.PolicyResult;
import com.debthunter.domain.PolicyStatus;
import com.debthunter.domain.PolicyViolation;
import com.debthunter.domain.ScanResult;
import com.debthunter.domain.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The field-by-field mapping from a bundle onto the domain model, shared by every schema bridge.
 *
 * <p>Fields every version has are mapped directly and are required. Fields the current version
 * added are handled by the {@code defaultMissing} switch: a bridge for an older version defaults
 * them (and records that it did), while the bridge for the current version treats their absence as
 * malformed.
 *
 * <p>Error messages name the offending field but never echo its value, because a bundle can carry
 * anything.
 */
final class BundleMapper {

  private final boolean defaultMissing;
  private final NormalisationReport.Builder report;

  BundleMapper(boolean defaultMissing, NormalisationReport.Builder report) {
    this.defaultMissing = defaultMissing;
    this.report = report;
  }

  ScanResult map(JsonNode bundle) {
    AnalysisRun run = run(object(bundle, "run", "run"));
    List<Finding> findings = findings(bundle.get("findings"));
    Map<String, DebtMetric> metrics = metrics(bundle.get("metrics"));
    PolicyResult policy = policy(object(bundle, "policy", "policy"));
    return new ScanResult(run, findings, metrics, policy);
  }

  // ---- run -------------------------------------------------------------------------------------

  private AnalysisRun run(JsonNode run) {
    List<EngineStatus> engines = engines(run.get("engines"));
    boolean degraded;
    if (present(run, "degraded")) {
      degraded = bool(run, "degraded", "run.degraded");
    } else {
      missing("run.degraded");
      degraded = engines.stream().anyMatch(e -> e.status() != EngineHealth.OK);
    }
    return new AnalysisRun(
        text(run, "id", "run.id"),
        text(run, "toolVersion", "run.toolVersion"),
        optionalText(run, "imageDigest"),
        instant(run, "timestamp", "run.timestamp"),
        text(run, "repository", "run.repository"),
        optionalText(run, "project"),
        text(run, "commit", "run.commit"),
        optionalText(run, "baseCommit"),
        optionalText(run, "branch"),
        optionalText(run, "pullRequest"),
        enumOf(
            HistoryDepth.class, text(run, "historyDepth", "run.historyDepth"), "run.historyDepth"),
        engines,
        degraded,
        optionalText(run, "baselineProvenance"));
  }

  private List<EngineStatus> engines(JsonNode node) {
    List<EngineStatus> engines = new ArrayList<>();
    for (JsonNode engine : array(node, "run.engines")) {
      requireObject(engine, "run.engines[]");
      engines.add(
          new EngineStatus(
              text(engine, "id", "run.engines[].id"),
              text(engine, "version", "run.engines[].version"),
              enumOf(
                  EngineHealth.class,
                  text(engine, "status", "run.engines[].status"),
                  "run.engines[].status"),
              integer(engine, "durationMs", "run.engines[].durationMs"),
              optionalText(engine, "reason")));
    }
    return engines;
  }

  // ---- findings --------------------------------------------------------------------------------

  private List<Finding> findings(JsonNode node) {
    List<Finding> findings = new ArrayList<>();
    for (JsonNode finding : array(node, "findings")) {
      requireObject(finding, "findings[]");
      Map<String, Object> evidence = evidence(finding);
      String categoryText = text(finding, "category", "findings[].category");
      Category category = category(categoryText, evidence);
      findings.add(
          new Finding(
              text(finding, "id", "findings[].id"),
              text(finding, "ruleId", "findings[].ruleId"),
              category,
              enumOf(
                  Severity.class,
                  text(finding, "severity", "findings[].severity"),
                  "findings[].severity"),
              number(
                  finding, "confidence", "findings[].confidence", NormalisationDefaults.CONFIDENCE),
              text(finding, "path", "findings[].path"),
              (int) integer(finding, "startLine", "findings[].startLine"),
              text(finding, "message", "findings[].message"),
              evidence,
              number(finding, "score", "findings[].score", NormalisationDefaults.SCORE),
              flag(finding, "isNew", "findings[].isNew", NormalisationDefaults.IS_NEW),
              text(finding, "fingerprint", "findings[].fingerprint")));
    }
    return findings;
  }

  /**
   * An older schema let a category be any text; the current one is a closed set. A value outside
   * the set maps to the closest equivalent, {@code CUSTOM}, and the original text is kept in the
   * finding's evidence so nothing is lost.
   */
  private Category category(String text, Map<String, Object> evidence) {
    try {
      return Category.valueOf(text);
    } catch (IllegalArgumentException e) {
      report.warn(
          "findings[].category",
          "not a known category; mapped to CUSTOM (original kept in evidence)");
      evidence.put("originalCategory", text);
      return Category.CUSTOM;
    }
  }

  private Map<String, Object> evidence(JsonNode finding) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    if (!present(finding, "evidence")) {
      missing("findings[].evidence");
      return evidence;
    }
    JsonNode node = finding.get("evidence");
    if (!node.isObject()) {
      throw malformed("findings[].evidence must be an object");
    }
    node.fields()
        .forEachRemaining(
            entry -> {
              Object value = plain(entry.getValue());
              if (value != null) {
                evidence.put(entry.getKey(), value);
              }
            });
    return evidence;
  }

  /** Converts a JSON value to plain Java types, dropping nulls (domain maps reject them). */
  private static Object plain(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isObject()) {
      Map<String, Object> map = new LinkedHashMap<>();
      node.fields()
          .forEachRemaining(
              e -> {
                Object v = plain(e.getValue());
                if (v != null) {
                  map.put(e.getKey(), v);
                }
              });
      return map;
    }
    if (node.isArray()) {
      List<Object> list = new ArrayList<>();
      for (JsonNode element : node) {
        Object v = plain(element);
        if (v != null) {
          list.add(v);
        }
      }
      return list;
    }
    if (node.isTextual()) {
      return node.textValue();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isIntegralNumber()) {
      return node.longValue();
    }
    return node.doubleValue();
  }

  // ---- metrics and policy ----------------------------------------------------------------------

  private Map<String, DebtMetric> metrics(JsonNode node) {
    Map<String, DebtMetric> metrics = new HashMap<>();
    if (node == null || node.isNull()) {
      missing("metrics");
      return metrics;
    }
    if (!node.isObject()) {
      throw malformed("metrics must be an object");
    }
    node.fields()
        .forEachRemaining(
            entry -> {
              JsonNode metric = entry.getValue();
              requireObject(metric, "metrics.*");
              String name = text(metric, "name", "metrics.*.name");
              metrics.put(
                  entry.getKey(),
                  new DebtMetric(
                      name,
                      requiredNumber(metric, "value", "metrics.*.value"),
                      text(metric, "scope", "metrics.*.scope")));
            });
    return metrics;
  }

  private PolicyResult policy(JsonNode policy) {
    String bundleVersion;
    if (present(policy, "bundleVersion")) {
      bundleVersion = text(policy, "bundleVersion", "policy.bundleVersion");
    } else {
      missing("policy.bundleVersion");
      bundleVersion = NormalisationDefaults.POLICY_BUNDLE_VERSION;
    }
    PolicyStatus status =
        enumOf(PolicyStatus.class, text(policy, "status", "policy.status"), "policy.status");
    List<PolicyViolation> reasons = new ArrayList<>();
    for (JsonNode reason : array(policy.get("reasons"), "policy.reasons")) {
      reasons.add(violation(reason));
    }
    return new PolicyResult(bundleVersion, status, reasons);
  }

  /**
   * An older schema let a policy reason be free text; the current one is a structured violation.
   * Text maps to the closest equivalent — a violation whose rule is that text and whose threshold
   * and actual are unknown.
   */
  private PolicyViolation violation(JsonNode reason) {
    if (reason.isTextual()) {
      if (!defaultMissing) {
        throw malformed("policy.reasons[] must be an object");
      }
      report.warn(
          "policy.reasons[]",
          "free-text reason; mapped to a violation with unknown threshold and actual");
      return new PolicyViolation(reason.textValue(), "unknown", "unknown", List.of());
    }
    requireObject(reason, "policy.reasons[]");
    List<String> findingIds = new ArrayList<>();
    if (present(reason, "findingIds")) {
      for (JsonNode id : array(reason.get("findingIds"), "policy.reasons[].findingIds")) {
        if (!id.isTextual()) {
          throw malformed("policy.reasons[].findingIds[] must be text");
        }
        findingIds.add(id.textValue());
      }
    } else {
      missing("policy.reasons[].findingIds");
    }
    return new PolicyViolation(
        text(reason, "rule", "policy.reasons[].rule"),
        textOrUnknown(reason, "threshold", "policy.reasons[].threshold"),
        textOrUnknown(reason, "actual", "policy.reasons[].actual"),
        findingIds);
  }

  // ---- readers ---------------------------------------------------------------------------------

  private static boolean present(JsonNode object, String name) {
    JsonNode value = object.get(name);
    return value != null && !value.isNull();
  }

  /** Records a defaulted field, or fails if this bridge's version required the field. */
  private void missing(String field) {
    if (!defaultMissing) {
      throw malformed(field + " is required");
    }
    report.defaulted(field);
  }

  private static JsonNode object(JsonNode parent, String name, String path) {
    JsonNode value = parent.get(name);
    if (value == null || !value.isObject()) {
      throw malformed(path + " is required and must be an object");
    }
    return value;
  }

  private static void requireObject(JsonNode node, String path) {
    if (node == null || !node.isObject()) {
      throw malformed(path + " must be an object");
    }
  }

  private static JsonNode array(JsonNode node, String path) {
    if (node == null || !node.isArray()) {
      throw malformed(path + " is required and must be an array");
    }
    return node;
  }

  private static String text(JsonNode object, String name, String path) {
    JsonNode value = object.get(name);
    if (value == null || !value.isTextual()) {
      throw malformed(path + " is required and must be text");
    }
    return value.textValue();
  }

  private static String optionalText(JsonNode object, String name) {
    JsonNode value = object.get(name);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private String textOrUnknown(JsonNode object, String name, String path) {
    if (!present(object, name)) {
      missing(path);
      return "unknown";
    }
    return text(object, name, path);
  }

  private static long integer(JsonNode object, String name, String path) {
    JsonNode value = object.get(name);
    if (value == null || !value.isIntegralNumber()) {
      throw malformed(path + " is required and must be an integer");
    }
    return value.longValue();
  }

  private static double requiredNumber(JsonNode object, String name, String path) {
    JsonNode value = object.get(name);
    if (value == null || !value.isNumber()) {
      throw malformed(path + " is required and must be a number");
    }
    return value.doubleValue();
  }

  private double number(JsonNode object, String name, String path, double fallback) {
    if (!present(object, name)) {
      missing(path);
      return fallback;
    }
    return requiredNumber(object, name, path);
  }

  private boolean flag(JsonNode object, String name, String path, boolean fallback) {
    if (!present(object, name)) {
      missing(path);
      return fallback;
    }
    return bool(object, name, path);
  }

  private static boolean bool(JsonNode object, String name, String path) {
    JsonNode value = object.get(name);
    if (value == null || !value.isBoolean()) {
      throw malformed(path + " must be true or false");
    }
    return value.booleanValue();
  }

  private static Instant instant(JsonNode object, String name, String path) {
    String value = text(object, name, path);
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw malformed(path + " is not an ISO-8601 instant");
    }
  }

  private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String path) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      throw malformed(path + " is not a recognised value");
    }
  }

  private static UnnormalisableBundleException malformed(String message) {
    return new UnnormalisableBundleException(message);
  }
}
