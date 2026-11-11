package com.debthunter.controlplane.store.baselines;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class BaselineRepositoryContract {

  static final String DIGEST = "sha256:" + "a".repeat(64);
  static final String DIGEST_2 = "sha256:" + "b".repeat(64);

  protected abstract BaselineRegistryRepository newRepository();

  private BaselineRegistryRepository repository;

  @BeforeEach
  void create() {
    repository = newRepository();
  }

  static BaselineRecord baseline(String commit, String digest, String at) {
    return new BaselineRecord(
        "acme/widgets",
        "default",
        commit,
        "registry.example.com/acme/baselines@" + digest,
        digest,
        Instant.parse(at),
        "scan run-1");
  }

  @Test
  void aSavedPointerReadsBack() {
    repository.save(baseline("c1", DIGEST, "2026-01-01T00:00:00Z"));

    assertThat(repository.find("acme/widgets", "default", "c1"))
        .contains(baseline("c1", DIGEST, "2026-01-01T00:00:00Z"));
  }

  @Test
  void savingTheSameCommitAgainReplacesThePointer() {
    repository.save(baseline("c1", DIGEST, "2026-01-01T00:00:00Z"));
    repository.save(baseline("c1", DIGEST_2, "2026-01-02T00:00:00Z"));

    assertThat(repository.find("acme/widgets", "default", "c1").get().digest()).isEqualTo(DIGEST_2);
  }

  @Test
  void latestIsTheMostRecentlyRecorded() {
    repository.save(baseline("c1", DIGEST, "2026-01-01T00:00:00Z"));
    repository.save(baseline("c2", DIGEST_2, "2026-01-05T00:00:00Z"));
    repository.save(baseline("c0", DIGEST, "2025-12-01T00:00:00Z"));

    assertThat(repository.latest("acme/widgets", "default").get().commit()).isEqualTo("c2");
  }

  @Test
  void anUnknownSliceHasNone() {
    assertThat(repository.latest("acme/widgets", "other")).isEmpty();
    assertThat(repository.find("acme/widgets", "default", "nope")).isEmpty();
  }
}
