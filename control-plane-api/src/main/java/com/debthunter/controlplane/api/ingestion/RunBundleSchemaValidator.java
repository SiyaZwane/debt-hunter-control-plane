package com.debthunter.controlplane.api.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a run bundle against the current schema version and the one before it (N-1).
 *
 * <p>The accepted window is by <em>major</em> version: when the data plane ships major N+1, the
 * control plane moves its current major up one, N+1 and N are accepted, and N-1 falls out. Anything
 * outside the window is reported as an unsupported version — distinct from a bundle that merely
 * fails its own version's schema.
 */
public final class RunBundleSchemaValidator {

  private static final Pattern VERSION = Pattern.compile("^(\\d{1,9})\\.(\\d{1,9})$");
  private static final int MAX_REPORTED_VIOLATIONS = 50;

  private final int currentMajor;
  private final Map<Integer, JsonSchema> schemasByMajor = new TreeMap<>(Comparator.reverseOrder());

  /**
   * Creates a validator accepting {@code currentMajor} and {@code currentMajor - 1}.
   *
   * @param currentMajor the newest schema major version accepted
   * @param source where schema text comes from
   * @throws IllegalStateException if there is no schema for the current major version
   */
  public RunBundleSchemaValidator(int currentMajor, SchemaSource source) {
    this.currentMajor = currentMajor;
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    for (int major = currentMajor; major >= Math.max(0, currentMajor - 1); major--) {
      int candidate = major;
      source
          .schemaFor(candidate)
          .ifPresent(text -> schemasByMajor.put(candidate, factory.getSchema(text)));
    }
    if (!schemasByMajor.containsKey(currentMajor)) {
      throw new IllegalStateException("No run-bundle schema for current major v" + currentMajor);
    }
  }

  /**
   * Creates a validator over the schemas shipped with this module.
   *
   * @param currentMajor the newest schema major version accepted
   * @return the validator
   */
  public static RunBundleSchemaValidator withClasspathSchemas(int currentMajor) {
    return new RunBundleSchemaValidator(currentMajor, new ClasspathSchemaSource());
  }

  /**
   * Validates a parsed bundle.
   *
   * @param bundle the bundle's JSON
   * @return whether it is valid, invalid, or declares an unsupported schema version
   */
  public SchemaValidationResult validate(JsonNode bundle) {
    JsonNode versionNode = bundle.get("schemaVersion");
    if (versionNode == null || !versionNode.isTextual()) {
      return invalid("$.schemaVersion", "is required and must be a string such as \"1.0\"");
    }
    String declared = versionNode.asText();
    Matcher matcher = VERSION.matcher(declared);
    if (!matcher.matches()) {
      return invalid("$.schemaVersion", "must look like <major>.<minor>, such as \"1.0\"");
    }
    int major = Integer.parseInt(matcher.group(1));
    JsonSchema schema = schemasByMajor.get(major);
    if (schema == null) {
      String reason =
          major > currentMajor
              ? "newer than this control plane understands"
              : "older than the oldest supported version";
      return new SchemaValidationResult.UnsupportedVersion(
          declared, reason, List.copyOf(schemasByMajor.keySet()));
    }

    Set<ValidationMessage> messages = schema.validate(bundle);
    if (messages.isEmpty()) {
      return new SchemaValidationResult.Valid(major, declared);
    }
    List<SchemaViolation> violations = new ArrayList<>();
    for (ValidationMessage message : messages) {
      violations.add(
          new SchemaViolation(message.getInstanceLocation().toString(), message.getMessage()));
    }
    violations.sort(
        Comparator.comparing(SchemaViolation::path).thenComparing(SchemaViolation::message));
    int total = violations.size();
    return new SchemaValidationResult.Invalid(
        violations.subList(0, Math.min(total, MAX_REPORTED_VIOLATIONS)), total);
  }

  private static SchemaValidationResult invalid(String path, String message) {
    return new SchemaValidationResult.Invalid(List.of(new SchemaViolation(path, message)), 1);
  }
}
