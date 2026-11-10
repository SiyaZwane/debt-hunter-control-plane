package com.debthunter.controlplane.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.testkit.RunBundleFixtures;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunBundleSchemaValidatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final RunBundleSchemaValidator validator =
      RunBundleSchemaValidator.withClasspathSchemas(1);

  @Test
  void aValidCurrentVersionBundleIsAccepted() {
    SchemaValidationResult result = validator.validate(json(RunBundleFixtures.validV1()));

    assertThat(result).isEqualTo(new SchemaValidationResult.Valid(1, "1.0"));
  }

  @Test
  void aValidPreviousVersionBundleIsAccepted() {
    SchemaValidationResult result = validator.validate(json(RunBundleFixtures.validV0()));

    assertThat(result).isEqualTo(new SchemaValidationResult.Valid(0, "0.9"));
  }

  @Test
  void aMinorVersionWithinTheCurrentMajorIsAccepted() {
    String bundle = RunBundleFixtures.withSchemaVersion(RunBundleFixtures.validV1(), "1.7");

    assertThat(validator.validate(json(bundle))).isInstanceOf(SchemaValidationResult.Valid.class);
  }

  @Test
  void aReportTheRealDataPlaneProducedIsAccepted() {
    // Guards against the two repositories' idea of the bundle shape drifting apart.
    SchemaValidationResult result =
        validator.validate(json(RunBundleFixtures.realDataPlaneReport()));

    assertThat(result).isInstanceOf(SchemaValidationResult.Valid.class);
  }

  @Test
  void unknownFieldsAreToleratedSoTheDataPlaneCanAddFieldsWithoutBreakingIngestion()
      throws Exception {
    ObjectNode bundle = (ObjectNode) MAPPER.readTree(RunBundleFixtures.validV1());
    bundle.put("aFieldAddedInTheFuture", "x");
    ((ObjectNode) bundle.get("run")).put("anotherNewField", 1);

    assertThat(validator.validate(bundle)).isInstanceOf(SchemaValidationResult.Valid.class);
  }

  @Test
  void aMissingRequiredFieldIsReportedWithItsLocation() {
    String bundle = RunBundleFixtures.withoutField(RunBundleFixtures.validV1(), "/run/repository");

    SchemaValidationResult result = validator.validate(json(bundle));

    assertThat(result).isInstanceOf(SchemaValidationResult.Invalid.class);
    SchemaValidationResult.Invalid invalid = (SchemaValidationResult.Invalid) result;
    assertThat(invalid.violations())
        .anySatisfy(
            violation -> {
              assertThat(violation.path()).isEqualTo("$.run");
              assertThat(violation.message()).contains("repository");
            });
  }

  @Test
  void aWronglyTypedFieldIsReportedAtItsPath() {
    String bundle =
        RunBundleFixtures.withValueAt(
            RunBundleFixtures.validV1(),
            "/findings/0/startLine",
            MAPPER.getNodeFactory().textNode("not a number"));

    SchemaValidationResult.Invalid invalid =
        (SchemaValidationResult.Invalid) validator.validate(json(bundle));

    assertThat(invalid.violations())
        .extracting(SchemaViolation::path)
        .contains("$.findings[0].startLine");
  }

  @Test
  void anOutOfRangeEnumValueIsRejected() {
    String bundle =
        RunBundleFixtures.withValueAt(
            RunBundleFixtures.validV1(),
            "/findings/0/severity",
            MAPPER.getNodeFactory().textNode("CATASTROPHIC"));

    assertThat(validator.validate(json(bundle))).isInstanceOf(SchemaValidationResult.Invalid.class);
  }

  @Test
  void aMissingSchemaVersionIsInvalidNotUnsupported() {
    String bundle = RunBundleFixtures.withoutField(RunBundleFixtures.validV1(), "/schemaVersion");

    SchemaValidationResult.Invalid invalid =
        (SchemaValidationResult.Invalid) validator.validate(json(bundle));

    assertThat(invalid.violations())
        .extracting(SchemaViolation::path)
        .containsExactly("$.schemaVersion");
  }

  @Test
  void aNonStringSchemaVersionIsInvalid() {
    String bundle =
        RunBundleFixtures.withValueAt(
            RunBundleFixtures.validV1(), "/schemaVersion", MAPPER.getNodeFactory().numberNode(1));

    assertThat(validator.validate(json(bundle))).isInstanceOf(SchemaValidationResult.Invalid.class);
  }

  @Test
  void aSchemaVersionThatIsNotMajorDotMinorIsInvalid() {
    String bundle = RunBundleFixtures.withSchemaVersion(RunBundleFixtures.validV1(), "banana");

    assertThat(validator.validate(json(bundle))).isInstanceOf(SchemaValidationResult.Invalid.class);
  }

  @Test
  void aNewerMajorVersionIsUnsupportedAndTheAcceptedVersionsAreListed() {
    String bundle = RunBundleFixtures.withSchemaVersion(RunBundleFixtures.validV1(), "2.0");

    SchemaValidationResult.UnsupportedVersion unsupported =
        (SchemaValidationResult.UnsupportedVersion) validator.validate(json(bundle));

    assertThat(unsupported.declared()).isEqualTo("2.0");
    assertThat(unsupported.reason()).contains("newer");
    assertThat(unsupported.supportedMajors()).containsExactly(1, 0);
  }

  @Test
  void whenTheCurrentMajorMovesUpTheOldestVersionFallsOutOfTheWindow() {
    SchemaSource classpath = new ClasspathSchemaSource();
    // Pretend the data plane has moved to major 2, reusing v1's schema as stand-in for "2".
    RunBundleSchemaValidator shifted =
        new RunBundleSchemaValidator(2, major -> classpath.schemaFor(major == 2 ? 1 : major));

    SchemaValidationResult previous = shifted.validate(json(RunBundleFixtures.validV1()));
    SchemaValidationResult tooOld = shifted.validate(json(RunBundleFixtures.validV0()));

    assertThat(previous).isInstanceOf(SchemaValidationResult.Valid.class);
    SchemaValidationResult.UnsupportedVersion unsupported =
        (SchemaValidationResult.UnsupportedVersion) tooOld;
    assertThat(unsupported.reason()).contains("older");
    assertThat(unsupported.supportedMajors()).containsExactly(2, 1);
  }

  @Test
  void violationsAreCappedAndTheTotalIsStillReported() throws Exception {
    String bundle = RunBundleFixtures.validV1WithFindings("run-1", "acme/widgets", 60);
    ObjectNode node = (ObjectNode) MAPPER.readTree(bundle);
    node.withArray("findings").forEach(finding -> ((ObjectNode) finding).remove("path"));

    SchemaValidationResult.Invalid invalid =
        (SchemaValidationResult.Invalid) validator.validate(node);

    assertThat(invalid.violations()).hasSize(50);
    assertThat(invalid.totalViolations()).isEqualTo(60);
  }

  @Test
  void violationsAreReportedInAStableOrder() {
    String bundle = RunBundleFixtures.withoutField(RunBundleFixtures.validV1(), "/run/repository");
    bundle = RunBundleFixtures.withoutField(bundle, "/policy/status");

    SchemaValidationResult.Invalid first =
        (SchemaValidationResult.Invalid) validator.validate(json(bundle));
    SchemaValidationResult.Invalid second =
        (SchemaValidationResult.Invalid) validator.validate(json(bundle));

    assertThat(first.violations()).isEqualTo(second.violations());
    assertThat(first.violations()).extracting(SchemaViolation::path).isSorted();
  }

  @Test
  void aValidatorWithNoSchemaForTheCurrentMajorCannotBeBuilt() {
    assertThatThrownBy(() -> new RunBundleSchemaValidator(5, major -> Optional.empty()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("v5");
  }

  private static JsonNode json(String text) {
    try {
      return MAPPER.readTree(text);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
