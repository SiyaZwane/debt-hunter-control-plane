package com.debthunter.controlplane.store.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class PolicyCatalogueContract {

  static final String DIGEST = "sha256:" + "c".repeat(64);

  protected abstract PolicyCatalogueRepository newRepository();

  private PolicyCatalogueRepository repository;

  @BeforeEach
  void create() {
    repository = newRepository();
  }

  @Test
  void aSavedBundleReadsBackIncludingItsRatification() {
    var bundle =
        new PolicyBundleRecord(
            "1.0",
            DIGEST,
            "approver",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-02-01T00:00:00Z"),
            "2.0");
    repository.save(bundle);

    assertThat(repository.find("1.0")).contains(bundle);
  }

  @Test
  void anUnratifiedBundleHasNoRatificationFields() {
    repository.save(new PolicyBundleRecord("1.0", DIGEST, null, null, null, null));

    assertThat(repository.find("1.0").get().isRatified()).isFalse();
  }

  @Test
  void savingAgainUpdatesTheRecordAndAllListsEveryVersion() {
    repository.save(new PolicyBundleRecord("1.0", DIGEST, null, null, null, null));
    repository.save(new PolicyBundleRecord("2.0", DIGEST, null, null, null, null));
    repository.save(
        new PolicyBundleRecord(
            "1.0", DIGEST, "approver", Instant.parse("2026-01-01T00:00:00Z"), null, null));

    assertThat(repository.all())
        .extracting(PolicyBundleRecord::version)
        .containsExactly("1.0", "2.0");
    assertThat(repository.find("1.0").get().isRatified()).isTrue();
  }

  @Test
  void anUnknownVersionIsAbsent() {
    assertThat(repository.find("9.9")).isEmpty();
  }
}
