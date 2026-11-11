package com.debthunter.controlplane.store.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class FindingStateRepositoryContract {

  protected abstract FindingStateRepository newRepository();

  private FindingStateRepository repository;

  @BeforeEach
  void createRepository() {
    repository = newRepository();
  }

  private static FindingStateRecord record(
      String slice, String fp, FindingState state, String commit) {
    return new FindingStateRecord("acme/widgets", slice, fp, state, commit);
  }

  @Test
  void aWrittenStateReadsBack() {
    repository.putAll(List.of(record("default", "fp-a", FindingState.NEW, "c1")));

    assertThat(repository.find("acme/widgets", "default", "fp-a"))
        .get()
        .extracting(FindingStateRecord::state)
        .isEqualTo(FindingState.NEW);
  }

  @Test
  void writingAgainReplacesTheRecordForThatFingerprint() {
    repository.putAll(List.of(record("default", "fp-a", FindingState.NEW, "c1")));
    repository.putAll(List.of(record("default", "fp-a", FindingState.OPEN, "c2")));

    assertThat(repository.statesFor("acme/widgets", "default")).hasSize(1);
    assertThat(repository.find("acme/widgets", "default", "fp-a").get().state())
        .isEqualTo(FindingState.OPEN);
  }

  @Test
  void slicesAreKeptApartSoTheSameFingerprintInTwoSlicesIsTwoFindings() {
    repository.putAll(
        List.of(
            record("api", "fp-a", FindingState.OPEN, "c1"),
            record("web", "fp-a", FindingState.RESOLVED, "c1")));

    assertThat(repository.find("acme/widgets", "api", "fp-a").get().state())
        .isEqualTo(FindingState.OPEN);
    assertThat(repository.find("acme/widgets", "web", "fp-a").get().state())
        .isEqualTo(FindingState.RESOLVED);
  }

  @Test
  void anUnknownFindingIsAbsent() {
    assertThat(repository.find("acme/widgets", "default", "nope")).isEmpty();
    assertThat(repository.statesFor("other/repo", "default")).isEmpty();
  }

  @Test
  void onlyNewOpenAndRegressedFindingsBlockGating() {
    repository.putAll(
        List.of(
            record("default", "new", FindingState.NEW, "c1"),
            record("default", "open", FindingState.OPEN, "c1"),
            record("default", "regressed", FindingState.REGRESSED, "c1"),
            record("default", "resolved", FindingState.RESOLVED, "c1"),
            record("default", "suppressed", FindingState.SUPPRESSED, "c1")));

    assertThat(repository.blockingFingerprints("acme/widgets", "default"))
        .containsExactlyInAnyOrder("new", "open", "regressed");
  }

  @Test
  void theStateMachineTableAllowsExactlyTheDocumentedMoves() {
    assertThat(FindingState.NEW.canMoveTo(FindingState.OPEN)).isTrue();
    assertThat(FindingState.OPEN.canMoveTo(FindingState.RESOLVED)).isTrue();
    assertThat(FindingState.RESOLVED.canMoveTo(FindingState.REGRESSED)).isTrue();
    assertThat(FindingState.REGRESSED.canMoveTo(FindingState.OPEN)).isTrue();
    assertThat(FindingState.SUPPRESSED.canMoveTo(FindingState.OPEN)).isTrue();

    assertThat(FindingState.RESOLVED.canMoveTo(FindingState.OPEN)).isFalse();
    assertThat(FindingState.RESOLVED.canMoveTo(FindingState.SUPPRESSED)).isFalse();
    assertThat(FindingState.SUPPRESSED.canMoveTo(FindingState.RESOLVED)).isFalse();
    assertThat(FindingState.OPEN.canMoveTo(FindingState.NEW)).isFalse();
    assertThat(FindingState.canStartIn(FindingState.NEW)).isTrue();
    assertThat(FindingState.canStartIn(FindingState.OPEN)).isFalse();
  }
}
