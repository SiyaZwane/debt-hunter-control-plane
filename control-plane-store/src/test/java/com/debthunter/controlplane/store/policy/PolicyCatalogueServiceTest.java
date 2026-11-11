package com.debthunter.controlplane.store.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PolicyCatalogueServiceTest {

  private static final String D1 = "sha256:" + "1".repeat(64);
  private static final String D2 = "sha256:" + "2".repeat(64);
  private static final String D3 = "sha256:" + "3".repeat(64);

  private final PolicyCatalogueService service =
      new PolicyCatalogueService(new InMemoryPolicyCatalogueRepository());

  private static Instant at(String date) {
    return Instant.parse(date + "T00:00:00Z");
  }

  private void ratifiedAndEffective(String version, String digest, String from) {
    service.register(version, digest);
    service.ratify(version, "governance-board", at("2025-12-01"));
    service.makeEffective(version, at(from));
  }

  @Test
  void aNewVersionIsRegisteredUnratified() {
    PolicyBundleRecord record = service.register("1.0", D1);

    assertThat(record.isRatified()).isFalse();
    assertThat(record.digest()).isEqualTo(D1);
  }

  @Test
  void registeringTheSameVersionTwiceIsRefused() {
    service.register("1.0", D1);

    assertThatThrownBy(() -> service.register("1.0", D2)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aDigestThatIsNotSha256IsRefused() {
    assertThatThrownBy(() -> service.register("1.0", "md5:abc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ratificationRecordsWhoAndWhen() {
    service.register("1.0", D1);
    service.ratify("1.0", "governance-board", at("2025-12-01"));

    assertThat(service.find("1.0").get().ratifiedBy()).isEqualTo("governance-board");
    assertThat(service.find("1.0").get().ratifiedAt()).isEqualTo(at("2025-12-01"));
  }

  @Test
  void aVersionCannotBeRatifiedTwiceOrWhenUnknown() {
    service.register("1.0", D1);
    service.ratify("1.0", "board", at("2025-12-01"));

    assertThatThrownBy(() -> service.ratify("1.0", "board", at("2025-12-02")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.ratify("9.9", "board", at("2025-12-02")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anUnratifiedVersionCannotTakeEffect() {
    service.register("1.0", D1);

    assertThatThrownBy(() -> service.makeEffective("1.0", at("2026-01-01")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(service.effectiveAt(at("2030-01-01"))).isEmpty();
  }

  @Test
  void makingANewVersionEffectiveMarksThePreviousOneSuperseded() {
    ratifiedAndEffective("1.0", D1, "2026-01-01");
    ratifiedAndEffective("2.0", D2, "2026-03-01");

    assertThat(service.find("1.0").get().supersededBy()).isEqualTo("2.0");
    assertThat(service.find("2.0").get().supersededBy()).isNull();
  }

  @Test
  void whichVersionAppliedDependsOnTheDateAsked() {
    ratifiedAndEffective("1.0", D1, "2026-01-01");
    ratifiedAndEffective("2.0", D2, "2026-03-01");

    assertThat(service.effectiveAt(at("2025-12-31"))).isEmpty();
    assertThat(service.effectiveAt(at("2026-02-01")))
        .get()
        .extracting(PolicyBundleRecord::version)
        .isEqualTo("1.0");
    assertThat(service.effectiveAt(at("2026-03-01")))
        .get()
        .extracting(PolicyBundleRecord::version)
        .isEqualTo("2.0");
  }

  @Test
  void aVersionSlottedInBetweenIsLinkedIntoTheChain() {
    ratifiedAndEffective("1.0", D1, "2026-01-01");
    ratifiedAndEffective("3.0", D3, "2026-06-01");
    ratifiedAndEffective("2.0", D2, "2026-03-01");

    assertThat(service.find("1.0").get().supersededBy()).isEqualTo("2.0");
    assertThat(service.find("2.0").get().supersededBy()).isEqualTo("3.0");
  }

  @Test
  void ratificationNeedsBothWhoAndWhen() {
    assertThatThrownBy(() -> new PolicyBundleRecord("1.0", D1, "board", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
