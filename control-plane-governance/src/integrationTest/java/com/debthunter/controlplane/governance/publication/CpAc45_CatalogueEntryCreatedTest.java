package com.debthunter.controlplane.governance.publication;

import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.governance.publication.PublishOutcome.Failed;
import com.debthunter.controlplane.governance.publication.PublishOutcome.Stage;
import com.debthunter.controlplane.store.policy.PolicyBundleRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-45: publishing creates a durable catalogue entry carrying the digest, the ratification
 * record and the effective date, and chains the superseded-by pointer.
 */
@Tag("integration")
class CpAc45_CatalogueEntryCreatedTest {

  private static final Instant DAY_1 = Instant.parse("2026-12-01T00:00:00Z");
  private static final Instant DAY_2 = Instant.parse("2027-01-01T00:00:00Z");
  private static final String V1 = policy("1.0").main("no-critical", "CRITICAL", 0).text();
  private static final String V2 = policy("2.0").main("no-critical", "CRITICAL", 0).text();

  private final PublicationFixture f = JdbcPublicationFixture.create();

  @Test
  void theEntryIsReadableFromPostgresAfterPublication() {
    f.publisher.publish(PublicationFixture.request(V1, DAY_1));

    PolicyBundleRecord record = f.catalogue.find("1.0").orElseThrow();

    assertThat(record.digest()).isEqualTo(BundleDigest.of(V1.getBytes(StandardCharsets.UTF_8)));
    assertThat(record.ratifiedBy()).isEqualTo("policy-board");
    assertThat(record.ratifiedAt()).isEqualTo(PublicationFixture.NOW);
    assertThat(record.effectiveFrom()).isEqualTo(DAY_1);
  }

  @Test
  void theBundleIsTheOneInForceFromItsEffectiveDate() {
    f.publisher.publish(PublicationFixture.request(V1, DAY_1));

    assertThat(f.catalogue.effectiveAt(DAY_1.minusSeconds(1))).isEmpty();
    assertThat(f.catalogue.effectiveAt(DAY_1))
        .get()
        .extracting(PolicyBundleRecord::version)
        .isEqualTo("1.0");
  }

  @Test
  void aNewerVersionSupersedesTheOlderAndTheHistoryStaysAnswerable() {
    f.publisher.publish(PublicationFixture.request(V1, DAY_1));
    f.publisher.publish(PublicationFixture.request(V2, DAY_2));

    assertThat(f.catalogue.find("1.0").orElseThrow().supersededBy()).isEqualTo("2.0");
    assertThat(f.catalogue.effectiveAt(DAY_1.plusSeconds(60)))
        .get()
        .extracting(PolicyBundleRecord::version)
        .isEqualTo("1.0");
    assertThat(f.catalogue.effectiveAt(DAY_2))
        .get()
        .extracting(PolicyBundleRecord::version)
        .isEqualTo("2.0");
  }

  @Test
  void republishingAVersionDoesNotDisturbTheExistingEntry() {
    f.publisher.publish(PublicationFixture.request(V1, DAY_1));
    PolicyBundleRecord before = f.catalogue.find("1.0").orElseThrow();

    Failed failed = (Failed) f.publisher.publish(PublicationFixture.request(V1, DAY_2));

    assertThat(failed.stage()).isEqualTo(Stage.VERSION_CONFLICT);
    assertThat(f.catalogue.find("1.0")).contains(before);
  }
}
