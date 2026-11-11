package com.debthunter.controlplane.store.quarantine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class QuarantineRepositoryContract {

  protected abstract QuarantineRepository newRepository();

  private QuarantineRepository repository;

  @BeforeEach
  void create() {
    repository = newRepository();
  }

  private static QuarantinedBundle bundle(String correlation, String run) {
    return new QuarantinedBundle(
        correlation,
        run,
        "acme/widgets",
        "svc-a",
        "1.0",
        "run.timestamp is not an ISO-8601 instant",
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:05Z"));
  }

  @Test
  void aQuarantinedBundleReadsBackByRunId() {
    repository.save(bundle("c1", "run-1"));

    assertThat(repository.forRun("run-1")).containsExactly(bundle("c1", "run-1"));
    assertThat(repository.forRun("other")).isEmpty();
  }

  @Test
  void recordingTheSameDeliveryTwiceKeepsOneRow() {
    repository.save(bundle("c1", "run-1"));
    repository.save(bundle("c1", "run-1"));

    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void aCorrectedResubmissionThatFailsAgainIsASecondEntryForTheSameRun() {
    repository.save(bundle("c1", "run-1"));
    repository.save(bundle("c2", "run-1"));

    assertThat(repository.forRun("run-1")).hasSize(2);
  }

  @Test
  void theRecordHasNoFieldThatCouldHoldTheBundleContent() {
    assertThat(
            Arrays.stream(QuarantinedBundle.class.getRecordComponents())
                .map(RecordComponent::getName))
        .noneMatch(n -> n.toLowerCase().contains("payload") || n.toLowerCase().contains("content"));
  }
}
