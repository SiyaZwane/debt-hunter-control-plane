package com.debthunter.controlplane.store.baselines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-28: the baseline registry stores a pointer and metadata, never the baseline itself. The
 * artefact lives in the container registry.
 */
class CpAc28_BaselineRegistryPointerOnlyTest {

  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String REFERENCE = "registry.example.com/acme/baselines@" + DIGEST;

  private final BaselineRegistryService service =
      new BaselineRegistryService(new InMemoryBaselineRegistryRepository());

  private static BaselineRecord record(String reference, String digest) {
    return new BaselineRecord(
        "acme/widgets",
        "default",
        "c1",
        reference,
        digest,
        Instant.parse("2026-01-01T00:00:00Z"),
        "scan");
  }

  @Test
  void cpAc28_theRecordHasNoFieldThatCouldHoldBaselineContent() {
    Set<Class<?>> allowed = Set.of(String.class, Instant.class);

    assertThat(
            Arrays.stream(BaselineRecord.class.getRecordComponents()).map(RecordComponent::getType))
        .allSatisfy(type -> assertThat(allowed).contains(type));
    assertThat(
            Arrays.stream(BaselineRecord.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactly(
            "repository",
            "projectSlice",
            "commit",
            "ociReference",
            "digest",
            "createdAt",
            "provenance");
  }

  @Test
  void cpAc28_aPointerIsRecordedAndReadBack() {
    service.register(record(REFERENCE, DIGEST));

    assertThat(service.forCommit("acme/widgets", "default", "c1"))
        .get()
        .satisfies(
            b -> {
              assertThat(b.ociReference()).isEqualTo(REFERENCE);
              assertThat(b.digest()).isEqualTo(DIGEST);
            });
    assertThat(service.latest("acme/widgets", "default")).isPresent();
  }

  @Test
  void cpAc28_somethingThatIsNotAnOciDigestReferenceIsRefused() {
    assertThatThrownBy(() -> record("{\"baseline\":\"inline content\"}", DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> record("registry.example.com/acme/baselines:latest", DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> record("https://example.com/baseline.json", DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cpAc28_theDigestMustMatchTheOneInTheReference() {
    assertThatThrownBy(() -> record(REFERENCE, "sha256:" + "b".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cpAc28_aTagOnlyReferenceIsRefusedBecauseATagCanBeMovedUnderYourFeet() {
    assertThatThrownBy(() -> record("registry.example.com/acme/baselines:v1", DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
