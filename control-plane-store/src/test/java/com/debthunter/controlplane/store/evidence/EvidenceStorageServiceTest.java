package com.debthunter.controlplane.store.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceStorageServiceTest {

  private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

  private final EvidenceStorageService service =
      new EvidenceStorageService(new InMemoryEvidenceRepository(), Duration.ofDays(30));

  private static PseudonymisedFinding finding(String id, Map<String, Object> evidence) {
    return new PseudonymisedFinding(
        id,
        "rule-" + id,
        Category.HOTSPOT,
        Severity.HIGH,
        0.9,
        "src/A.java",
        0,
        "msg",
        evidence,
        1.0,
        true,
        "fp-" + id,
        List.of());
  }

  @Test
  void evidenceIsStoredInTheHotTierForTheConfiguredTtl() {
    int stored =
        service.store(
            "r1", "acme/widgets", List.of(finding("a", Map.of("changeFrequency", 3.0))), T0);

    assertThat(stored).isEqualTo(1);
    assertThat(service.hotFor("r1"))
        .singleElement()
        .satisfies(s -> assertThat(s.expiresAt()).isEqualTo(T0.plus(Duration.ofDays(30))));
  }

  @Test
  void aFindingWithNoEvidenceStoresNothing() {
    assertThat(service.store("r1", "acme/widgets", List.of(finding("a", Map.of())), T0)).isZero();
    assertThat(service.hotFor("r1")).isEmpty();
  }

  @Test
  void afterTheTtlTheDetailIsGoneAndOnlyACountRemains() {
    service.store("r1", "acme/widgets", List.of(finding("a", Map.of("changeFrequency", 3.0))), T0);

    int moved = service.expire(T0.plus(Duration.ofDays(31)));

    assertThat(moved).isEqualTo(1);
    assertThat(service.hotFor("r1")).isEmpty();
    assertThat(service.warmSummaryFor("acme/widgets"))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.findingCount()).isEqualTo(1);
              assertThat(s.ruleId()).isEqualTo("rule-a");
            });
  }

  @Test
  void expiryIsJudgedByTheDateGivenNotTheWallClock() {
    service.store("r1", "acme/widgets", List.of(finding("a", Map.of("changeFrequency", 3.0))), T0);

    assertThat(service.expire(T0.plus(Duration.ofDays(29)))).isZero();
    assertThat(service.expire(T0.plus(Duration.ofDays(30)))).isEqualTo(1);
  }

  @Test
  void aNonPositiveTtlIsRefused() {
    assertThatThrownBy(
            () -> new EvidenceStorageService(new InMemoryEvidenceRepository(), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theWarmTierHoldsNoEvidenceDetail() {
    assertThat(
            java.util.Arrays.stream(EvidenceSummary.class.getRecordComponents())
                .map(c -> c.getName()))
        .containsExactly("repository", "ruleId", "severity", "day", "findingCount");
  }
}
