package com.debthunter.controlplane.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.normalise.NormalisationDefaults;
import com.debthunter.controlplane.ingest.normalise.NormalisedRunBundle;
import com.debthunter.controlplane.ingest.normalise.VolatileFieldStripper;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-08: the same run delivered in the current schema and in the previous one normalises to
 * equivalent output. "Equivalent" has a precise meaning here: once the volatile fields (run id,
 * timestamp, durations) are stripped, the two are identical — the only thing that can legitimately
 * differ is a field the older version cannot carry, and that is a recorded default.
 */
@Tag("integration")
class CpAc08_EquivalentOutputAcrossVersionsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final ObjectMapper CANONICAL =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  private final VolatileFieldStripper stripper = new VolatileFieldStripper();

  /** The previous-version bundle, restated in the current schema with the defaults spelled out. */
  private static String restatedInCurrentSchema(String previous) throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(previous);
    root.put("schemaVersion", "1.0");
    ((ObjectNode) root.get("run")).put("degraded", false);
    root.putObject("metrics");
    for (JsonNode finding : (ArrayNode) root.get("findings")) {
      ObjectNode f = (ObjectNode) finding;
      f.put("confidence", NormalisationDefaults.CONFIDENCE);
      f.putObject("evidence");
      f.put("score", NormalisationDefaults.SCORE);
      f.put("isNew", NormalisationDefaults.IS_NEW);
    }
    ((ObjectNode) root.get("policy"))
        .put("bundleVersion", NormalisationDefaults.POLICY_BUNDLE_VERSION);
    return JSON.writeValueAsString(root);
  }

  private JsonNode canonical(NormalisedRunBundle bundle) {
    return stripper.strip(CANONICAL.valueToTree(bundle.scan()));
  }

  @Test
  void cpAc08_theSameRunInEitherVersionNormalisesToTheSameOutput() throws Exception {
    String previous = RunBundleFixtures.validV0("run-prev", "acme/widgets");
    // Same facts, different run id and timestamp: volatile fields must not matter.
    String current =
        RunBundleFixtures.withValueAt(
            restatedInCurrentSchema(RunBundleFixtures.validV0("run-curr", "acme/widgets")),
            "/run/timestamp",
            JSON.getNodeFactory().textNode("2026-03-03T03:03:03Z"));

    try (NormalisingPipeline pipeline = new NormalisingPipeline()) {
      NormalisedRunBundle fromPrevious = pipeline.deliver(previous);
      NormalisedRunBundle fromCurrent = pipeline.deliver(current);

      assertThat(fromPrevious.scan().run().id()).isNotEqualTo(fromCurrent.scan().run().id());
      assertThat(canonical(fromPrevious)).isEqualTo(canonical(fromCurrent));
      assertThat(fromPrevious.report().isClean()).isFalse();
      assertThat(fromCurrent.report().isClean()).isTrue();
    }
  }

  @Test
  void cpAc08_aDifferenceThatIsNotVolatileIsNotHiddenByNormalisation() throws Exception {
    String previous = RunBundleFixtures.validV0("run-prev", "acme/widgets");
    String current =
        RunBundleFixtures.withValueAt(
            restatedInCurrentSchema(RunBundleFixtures.validV0("run-curr", "acme/widgets")),
            "/findings/0/severity",
            JSON.getNodeFactory().textNode("LOW"));

    try (NormalisingPipeline pipeline = new NormalisingPipeline()) {
      NormalisedRunBundle fromPrevious = pipeline.deliver(previous);
      NormalisedRunBundle fromCurrent = pipeline.deliver(current);

      assertThat(canonical(fromPrevious)).isNotEqualTo(canonical(fromCurrent));
    }
  }

  @Test
  void cpAc08_aCurrentBundleWithRealValuesAgreesWithItsPreviousFormOnEveryFieldBothCarry()
      throws Exception {
    try (NormalisingPipeline pipeline = new NormalisingPipeline()) {
      NormalisedRunBundle fromPrevious =
          pipeline.deliver(RunBundleFixtures.validV0("run-a", "acme/widgets"));
      NormalisedRunBundle fromCurrent =
          pipeline.deliver(RunBundleFixtures.validV1("run-b", "acme/widgets"));

      var previousFinding = fromPrevious.scan().findings().get(0);
      var currentFinding = fromCurrent.scan().findings().get(0);
      assertThat(previousFinding.ruleId()).isEqualTo(currentFinding.ruleId());
      assertThat(previousFinding.category()).isEqualTo(currentFinding.category());
      assertThat(previousFinding.severity()).isEqualTo(currentFinding.severity());
      assertThat(previousFinding.path()).isEqualTo(currentFinding.path());
      assertThat(previousFinding.startLine()).isEqualTo(currentFinding.startLine());
      assertThat(previousFinding.message()).isEqualTo(currentFinding.message());
      assertThat(previousFinding.fingerprint()).isEqualTo(currentFinding.fingerprint());
      assertThat(fromPrevious.scan().run().repository())
          .isEqualTo(fromCurrent.scan().run().repository());
      assertThat(fromPrevious.scan().run().commit()).isEqualTo(fromCurrent.scan().run().commit());
      assertThat(fromPrevious.scan().policy().status())
          .isEqualTo(fromCurrent.scan().policy().status());
      // What differs is exactly what the previous version could not carry, and it is on record.
      assertThat(previousFinding.confidence()).isNotEqualTo(currentFinding.confidence());
      assertThat(fromPrevious.report().wasDefaulted("findings[].confidence")).isTrue();
    }
  }
}
