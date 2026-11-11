package com.debthunter.controlplane.ingest.normalise;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.domain.ScanResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a run bundle of any supported schema version onto the one internal representation, so
 * nothing downstream ever needs to know there is more than one schema.
 *
 * <p>The schema version is read from the bundle itself and selects a {@link SchemaBridge}. A
 * version with no bridge, or a bundle a bridge cannot map, is an {@link
 * UnnormalisableBundleException}: permanent, so a caller must quarantine the bundle rather than
 * retry it.
 */
public final class RunNormaliser {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private final Map<Integer, SchemaBridge> bridges = new HashMap<>();

  /** Creates a normaliser that understands the current schema and the one before it. */
  public RunNormaliser() {
    this(List.of(new CurrentSchemaBridge(), new PreviousSchemaBridge()));
  }

  /**
   * Creates a normaliser over an explicit set of bridges.
   *
   * @param bridges one bridge per supported schema major version
   * @throws IllegalArgumentException if two bridges claim the same major version
   */
  public RunNormaliser(List<SchemaBridge> bridges) {
    for (SchemaBridge bridge : bridges) {
      if (this.bridges.put(bridge.major(), bridge) != null) {
        throw new IllegalArgumentException("Two bridges for schema major " + bridge.major());
      }
    }
  }

  /**
   * Normalises a bundle taken off the intake queue.
   *
   * @param envelope the bundle and what the edge learned about it
   * @return the version-independent bundle, with a report of what was defaulted or approximated
   * @throws UnnormalisableBundleException if the bundle can never be normalised
   */
  public NormalisedRunBundle normalise(IntakeEnvelope envelope) {
    JsonNode bundle = parse(envelope.payloadJson());
    String schemaVersion = schemaVersion(bundle);
    SchemaBridge bridge = bridges.get(major(schemaVersion));
    if (bridge == null) {
      throw new UnnormalisableBundleException(
          "No bridge for schema version " + abbreviated(schemaVersion));
    }
    NormalisationReport.Builder report = new NormalisationReport.Builder();
    ScanResult scan = bridge.bridge(bundle, report);
    return new NormalisedRunBundle(
        scan,
        new BundleProvenance(
            envelope.correlationId(),
            envelope.workloadIdentity(),
            envelope.receivedAt(),
            schemaVersion),
        report.build());
  }

  private static JsonNode parse(String json) {
    try {
      JsonNode root = MAPPER.readTree(json);
      if (root == null || !root.isObject()) {
        throw new UnnormalisableBundleException("The bundle is not a JSON object");
      }
      return root;
    } catch (JsonProcessingException e) {
      throw new UnnormalisableBundleException("The bundle is not valid JSON", e);
    }
  }

  private static String schemaVersion(JsonNode bundle) {
    JsonNode version = bundle.get("schemaVersion");
    if (version == null || !version.isTextual()) {
      throw new UnnormalisableBundleException("schemaVersion is required and must be text");
    }
    return version.textValue();
  }

  private static int major(String schemaVersion) {
    int dot = schemaVersion.indexOf('.');
    String major = dot < 0 ? schemaVersion : schemaVersion.substring(0, dot);
    try {
      return Integer.parseInt(major);
    } catch (NumberFormatException e) {
      throw new UnnormalisableBundleException(
          "schemaVersion is not of the form <major>.<minor>: " + abbreviated(schemaVersion));
    }
  }

  /** Version text comes from outside; keep it short before it goes into a message. */
  private static String abbreviated(String value) {
    String cleaned = value.replaceAll("\\p{Cntrl}", "?");
    return cleaned.length() <= 20 ? cleaned : cleaned.substring(0, 20) + "…";
  }
}
