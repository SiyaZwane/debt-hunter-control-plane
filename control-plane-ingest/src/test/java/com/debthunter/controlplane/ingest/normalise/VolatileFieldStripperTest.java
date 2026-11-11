package com.debthunter.controlplane.ingest.normalise;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.testkit.RunBundleFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class VolatileFieldStripperTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final VolatileFieldStripper stripper = new VolatileFieldStripper();

  private static JsonNode parse(String json) throws Exception {
    return JSON.readTree(json);
  }

  @Test
  void removesTheRunIdTheTimestampAndEveryEngineDuration() throws Exception {
    JsonNode stripped = stripper.strip(parse(RunBundleFixtures.validV1()));

    assertThat(stripped.at("/run").has("id")).isFalse();
    assertThat(stripped.at("/run").has("timestamp")).isFalse();
    assertThat(stripped.at("/run/engines/0").has("durationMs")).isFalse();
  }

  @Test
  void leavesEverythingElseAlone() throws Exception {
    JsonNode original = parse(RunBundleFixtures.validV1());

    JsonNode stripped = stripper.strip(original);

    assertThat(stripped.at("/run/repository")).isEqualTo(original.at("/run/repository"));
    assertThat(stripped.at("/run/engines/0/version"))
        .isEqualTo(original.at("/run/engines/0/version"));
    assertThat(stripped.at("/findings")).isEqualTo(original.at("/findings"));
    assertThat(stripped.at("/policy")).isEqualTo(original.at("/policy"));
  }

  @Test
  void doesNotModifyItsInput() throws Exception {
    JsonNode original = parse(RunBundleFixtures.validV1());
    JsonNode snapshot = original.deepCopy();

    stripper.strip(original);

    assertThat(original).isEqualTo(snapshot);
  }

  @Test
  void twoRunsOfTheSameCodeAreEqualOnceStripped() throws Exception {
    JsonNode first = parse(RunBundleFixtures.validV1("run-1", "acme/widgets"));
    JsonNode second =
        parse(
            RunBundleFixtures.withValueAt(
                RunBundleFixtures.withValueAt(
                    RunBundleFixtures.validV1("run-2", "acme/widgets"),
                    "/run/timestamp",
                    JSON.getNodeFactory().textNode("2026-02-02T02:02:02Z")),
                "/run/engines/0/durationMs",
                JSON.getNodeFactory().numberNode(987)));

    assertThat(first).isNotEqualTo(second);
    assertThat(stripper.strip(first)).isEqualTo(stripper.strip(second));
  }

  @Test
  void aDifferenceThatIsNotVolatileSurvivesStripping() throws Exception {
    JsonNode first = parse(RunBundleFixtures.validV1());
    JsonNode second =
        parse(
            RunBundleFixtures.withValueAt(
                RunBundleFixtures.validV1(),
                "/findings/0/severity",
                JSON.getNodeFactory().textNode("LOW")));

    assertThat(stripper.strip(first)).isNotEqualTo(stripper.strip(second));
  }

  @Test
  void toleratesAShapeWithoutARunOrEngines() throws Exception {
    assertThat(stripper.strip(parse("{}"))).isEqualTo(parse("{}"));
    assertThat(stripper.strip(parse("{\"run\":{\"id\":\"x\"}}"))).isEqualTo(parse("{\"run\":{}}"));
  }

  @Test
  void strippingTwiceChangesNothingMore() throws Exception {
    JsonNode once = stripper.strip(parse(RunBundleFixtures.validV1()));

    assertThat(stripper.strip(once)).isEqualTo(once);
  }
}
