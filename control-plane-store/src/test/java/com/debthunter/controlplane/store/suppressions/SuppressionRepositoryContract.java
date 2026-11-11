package com.debthunter.controlplane.store.suppressions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class SuppressionRepositoryContract {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  protected abstract SuppressionRepository newRepository();

  private SuppressionRepository repository;

  @BeforeEach
  void create() {
    repository = newRepository();
  }

  private static Suppression suppression(String id, String fp) {
    return new Suppression(
        id, "acme/widgets", fp, "accepted risk", T0, T0.plus(Duration.ofDays(30)), 90, null);
  }

  @Test
  void aSavedSuppressionReadsBack() {
    repository.save(suppression("s1", "fp-a"));

    assertThat(repository.find("acme/widgets", "fp-a")).contains(suppression("s1", "fp-a"));
  }

  @Test
  void thereIsAtMostOneSuppressionPerFinding() {
    repository.save(suppression("s1", "fp-a"));
    repository.save(suppression("s2", "fp-a"));

    assertThat(repository.find("acme/widgets", "fp-a").get().id()).isEqualTo("s2");
  }

  @Test
  void deletingRemovesIt() {
    repository.save(suppression("s1", "fp-a"));

    assertThat(repository.delete("s1")).isTrue();
    assertThat(repository.find("acme/widgets", "fp-a")).isEmpty();
    assertThat(repository.delete("s1")).isFalse();
  }

  @Test
  void markingNotifiedTakesItOutOfTheUnnotifiedList() {
    repository.save(suppression("s1", "fp-a"));
    repository.save(suppression("s2", "fp-b"));

    repository.markNotified("s1", T0.plus(Duration.ofDays(5)));

    assertThat(repository.findUnnotified()).extracting(Suppression::id).containsExactly("s2");
    assertThat(repository.find("acme/widgets", "fp-a").get().preExpiryNotifiedAt())
        .isEqualTo(T0.plus(Duration.ofDays(5)));
  }
}
