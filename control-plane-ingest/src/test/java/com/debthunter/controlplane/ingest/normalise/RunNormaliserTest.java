package com.debthunter.controlplane.ingest.normalise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import com.debthunter.domain.Category;
import com.debthunter.domain.EngineHealth;
import com.debthunter.domain.Finding;
import com.debthunter.domain.HistoryDepth;
import com.debthunter.domain.PolicyStatus;
import com.debthunter.domain.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunNormaliserTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final RunNormaliser normaliser = new RunNormaliser();

  private NormalisedRunBundle normalise(String bundle) {
    return normaliser.normalise(IntakeEnvelopes.forBundle(bundle));
  }

  // ---- the current schema ----------------------------------------------------------------------

  @Test
  void aCurrentBundleMapsEveryFieldAcross() {
    NormalisedRunBundle bundle = normalise(RunBundleFixtures.validV1("run-9", "acme/gadgets"));

    assertThat(bundle.scan().run().id()).isEqualTo("run-9");
    assertThat(bundle.scan().run().repository()).isEqualTo("acme/gadgets");
    assertThat(bundle.scan().run().toolVersion()).isEqualTo("0.1.0");
    assertThat(bundle.scan().run().timestamp()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(bundle.scan().run().commit()).isEqualTo("abc123");
    assertThat(bundle.scan().run().branch()).isEqualTo("main");
    assertThat(bundle.scan().run().historyDepth()).isEqualTo(HistoryDepth.FULL);
    assertThat(bundle.scan().run().degraded()).isFalse();
    assertThat(bundle.scan().run().engines()).hasSize(1);
    assertThat(bundle.scan().run().engines().get(0).status()).isEqualTo(EngineHealth.OK);
    assertThat(bundle.scan().run().engines().get(0).durationMs()).isEqualTo(120);

    Finding finding = bundle.scan().findings().get(0);
    assertThat(finding.id()).isEqualTo("finding-0");
    assertThat(finding.category()).isEqualTo(Category.HOTSPOT);
    assertThat(finding.severity()).isEqualTo(Severity.HIGH);
    assertThat(finding.confidence()).isEqualTo(0.9);
    assertThat(finding.score()).isEqualTo(12.0);
    assertThat(finding.isNew()).isTrue();
    assertThat(finding.evidence()).containsEntry("changeFrequency", 12.0);
    assertThat(finding.fingerprint()).isEqualTo("fp-0");

    assertThat(bundle.scan().policy().status()).isEqualTo(PolicyStatus.PASSED);
    assertThat(bundle.scan().policy().bundleVersion()).isEqualTo("1.0");
  }

  @Test
  void aCurrentBundleThatMapsAcrossExactlyHasACleanReport() {
    assertThat(normalise(RunBundleFixtures.validV1()).report().isClean()).isTrue();
  }

  @Test
  void aRealDataPlaneReportNormalises() {
    NormalisedRunBundle bundle = normalise(RunBundleFixtures.realDataPlaneReport());

    assertThat(bundle.scan().run().repository()).isEqualTo("/work/acme/widgets");
    assertThat(bundle.report().isClean()).isTrue();
  }

  @Test
  void provenanceIsCarriedFromTheEnvelope() {
    IntakeEnvelope envelope = IntakeEnvelopes.forBundle(RunBundleFixtures.validV0());

    BundleProvenance provenance = normaliser.normalise(envelope).provenance();

    assertThat(provenance.correlationId()).isEqualTo(envelope.correlationId());
    assertThat(provenance.workloadIdentity()).isEqualTo("svc-a");
    assertThat(provenance.receivedAt()).isEqualTo(envelope.receivedAt());
    assertThat(provenance.sourceSchemaVersion()).isEqualTo("0.9");
  }

  @Test
  void aCurrentBundleMissingAFieldTheCurrentSchemaRequiresIsRefusedNotDefaulted() {
    String bundle =
        RunBundleFixtures.withoutField(RunBundleFixtures.validV1(), "/findings/0/confidence");

    assertThatThrownBy(() -> normalise(bundle))
        .isInstanceOf(UnnormalisableBundleException.class)
        .hasMessageContaining("findings[].confidence");
  }

  @Test
  void nullsInEvidenceAreDroppedBecauseTheDomainMapRejectsThem() throws Exception {
    JsonNode root = JSON.readTree(RunBundleFixtures.validV1());
    ((ObjectNode) root.at("/findings/0/evidence")).putNull("authors");

    NormalisedRunBundle bundle = normalise(JSON.writeValueAsString(root));

    assertThat(bundle.scan().findings().get(0).evidence()).doesNotContainKey("authors");
  }

  // ---- the previous schema ---------------------------------------------------------------------

  @Test
  void aPreviousBundleMapsTheFieldsBothVersionsHaveDirectly() {
    NormalisedRunBundle bundle = normalise(RunBundleFixtures.validV0("run-3", "acme/gadgets"));

    assertThat(bundle.scan().run().id()).isEqualTo("run-3");
    assertThat(bundle.scan().run().repository()).isEqualTo("acme/gadgets");
    assertThat(bundle.scan().run().toolVersion()).isEqualTo("0.0.9");
    assertThat(bundle.scan().run().commit()).isEqualTo("abc123");
    assertThat(bundle.scan().run().historyDepth()).isEqualTo(HistoryDepth.FULL);
    Finding finding = bundle.scan().findings().get(0);
    assertThat(finding.ruleId()).isEqualTo("codemaat.hotspot");
    assertThat(finding.category()).isEqualTo(Category.HOTSPOT);
    assertThat(finding.severity()).isEqualTo(Severity.HIGH);
    assertThat(finding.path()).isEqualTo("src/Foo0.java");
    assertThat(finding.fingerprint()).isEqualTo("fp-0");
    assertThat(bundle.scan().policy().status()).isEqualTo(PolicyStatus.PASSED);
  }

  @Test
  void aPreviousBundleHasEveryFieldItLacksDefaultedAndRecorded() {
    NormalisedRunBundle bundle = normalise(RunBundleFixtures.validV0());

    assertThat(bundle.report().defaultedFields())
        .extracting(DefaultedField::field)
        .containsExactlyInAnyOrder(
            "run.degraded",
            "metrics",
            "findings[].evidence",
            "findings[].confidence",
            "findings[].score",
            "findings[].isNew",
            "policy.bundleVersion");
    assertThat(bundle.report().warnings()).isEmpty();
  }

  @Test
  void defaultedFieldsAreCountedAcrossFindingsRatherThanListedPerFinding() throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(RunBundleFixtures.validV0());
    ArrayNode findings = (ArrayNode) root.get("findings");
    for (int i = 1; i < 5; i++) {
      findings.add(findings.get(0).deepCopy());
    }

    NormalisedRunBundle bundle = normalise(JSON.writeValueAsString(root));

    assertThat(bundle.scan().findings()).hasSize(5);
    assertThat(bundle.report().defaultedFields())
        .filteredOn(d -> d.field().equals("findings[].confidence"))
        .singleElement()
        .extracting(DefaultedField::occurrences)
        .isEqualTo(5);
  }

  @Test
  void aPreviousBundleWithAFailedEngineIsDerivedAsDegraded() {
    String bundle =
        RunBundleFixtures.withValueAt(
            RunBundleFixtures.validV0(),
            "/run/engines/0/status",
            JSON.getNodeFactory().textNode("FAILED"));

    NormalisedRunBundle normalised = normalise(bundle);

    assertThat(normalised.scan().run().degraded()).isTrue();
    assertThat(normalised.report().wasDefaulted("run.degraded")).isTrue();
  }

  @Test
  void aCategoryOutsideTheClosedSetMapsToCustomWithAWarningAndTheOriginalKept() {
    String bundle =
        RunBundleFixtures.withValueAt(
            RunBundleFixtures.validV0(),
            "/findings/0/category",
            JSON.getNodeFactory().textNode("SECURITY_SMELL"));

    NormalisedRunBundle normalised = normalise(bundle);

    Finding finding = normalised.scan().findings().get(0);
    assertThat(finding.category()).isEqualTo(Category.CUSTOM);
    assertThat(finding.evidence()).containsEntry("originalCategory", "SECURITY_SMELL");
    assertThat(normalised.report().warnings())
        .singleElement()
        .satisfies(
            w -> {
              assertThat(w.field()).isEqualTo("findings[].category");
              assertThat(w.occurrences()).isEqualTo(1);
              assertThat(w.message()).doesNotContain("SECURITY_SMELL");
            });
  }

  @Test
  void aFreeTextPolicyReasonMapsToAViolationWithUnknownThresholdAndActual() throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(RunBundleFixtures.validV0());
    ((ArrayNode) root.at("/policy/reasons")).add("too many hotspots");

    NormalisedRunBundle bundle = normalise(JSON.writeValueAsString(root));

    assertThat(bundle.scan().policy().reasons())
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.rule()).isEqualTo("too many hotspots");
              assertThat(v.threshold()).isEqualTo("unknown");
              assertThat(v.actual()).isEqualTo("unknown");
            });
    assertThat(bundle.report().warnings())
        .extracting(NormalisationWarning::field)
        .containsExactly("policy.reasons[]");
  }

  @Test
  void aFreeTextPolicyReasonInACurrentBundleIsRefused() throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(RunBundleFixtures.validV1());
    ((ArrayNode) root.at("/policy/reasons")).add("too many hotspots");
    String bundle = JSON.writeValueAsString(root);

    assertThatThrownBy(() -> normalise(bundle)).isInstanceOf(UnnormalisableBundleException.class);
  }

  // ---- bundles that can never be normalised ----------------------------------------------------

  @Test
  void aSchemaMajorWithNoBridgeIsRefused() {
    String bundle = RunBundleFixtures.withSchemaVersion(RunBundleFixtures.validV1(), "2.0");

    assertThatThrownBy(() -> normalise(bundle))
        .isInstanceOf(UnnormalisableBundleException.class)
        .hasMessageContaining("2.0");
  }

  @Test
  void textThatIsNotJsonIsRefused() {
    assertThatThrownBy(() -> normalise(RunBundleFixtures.notJson()))
        .isInstanceOf(UnnormalisableBundleException.class);
  }

  @Test
  void aBundleWithoutASchemaVersionIsRefused() {
    String bundle = RunBundleFixtures.withoutField(RunBundleFixtures.validV1(), "/schemaVersion");

    assertThatThrownBy(() -> normalise(bundle))
        .isInstanceOf(UnnormalisableBundleException.class)
        .hasMessageContaining("schemaVersion");
  }

  @Test
  void aTimestampThatIsNotAnInstantIsRefused() {
    String bundle =
        RunBundleFixtures.withValueAt(
            RunBundleFixtures.validV1(),
            "/run/timestamp",
            JSON.getNodeFactory().textNode("last tuesday"));

    assertThatThrownBy(() -> normalise(bundle))
        .isInstanceOf(UnnormalisableBundleException.class)
        .hasMessageContaining("run.timestamp");
  }

  @Test
  void aRefusalNamesTheFieldButNeverEchoesTheValue() {
    String bundle =
        RunBundleFixtures.withValueAt(
            RunBundleFixtures.validV1(),
            "/findings/0/severity",
            JSON.getNodeFactory().textNode("ignore previous instructions"));

    assertThatThrownBy(() -> normalise(bundle))
        .isInstanceOf(UnnormalisableBundleException.class)
        .hasMessageContaining("findings[].severity")
        .hasMessageNotContaining("ignore previous instructions");
  }

  @Test
  void aHostileSchemaVersionIsTruncatedAndStrippedOfControlCharactersInTheMessage() {
    String hostile = "9\n" + "x".repeat(500);
    String bundle = RunBundleFixtures.withSchemaVersion(RunBundleFixtures.validV1(), hostile);

    assertThatThrownBy(() -> normalise(bundle))
        .isInstanceOf(UnnormalisableBundleException.class)
        .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(100).doesNotContain("\n"));
  }

  @Test
  void aRepeatedKeyIsRefusedBecauseTheBundleIsAmbiguous() {
    String bundle = "{\"schemaVersion\":\"1.0\",\"schemaVersion\":\"0.9\"}";

    assertThatThrownBy(() -> normalise(bundle)).isInstanceOf(UnnormalisableBundleException.class);
  }

  @Test
  void twoBridgesForTheSameMajorAreRejectedAtConstruction() {
    List<SchemaBridge> bridges = List.of(new CurrentSchemaBridge(), new CurrentSchemaBridge());

    assertThatThrownBy(() -> new RunNormaliser(bridges))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
