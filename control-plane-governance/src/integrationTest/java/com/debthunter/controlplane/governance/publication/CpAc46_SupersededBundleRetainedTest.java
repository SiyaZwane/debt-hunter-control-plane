package com.debthunter.controlplane.governance.publication;

import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-46: once a bundle is superseded it is still retrievable, by digest and by version, and
 * still verifies. A pipeline pinned to the old tool major can keep using it.
 */
@Tag("integration")
class CpAc46_SupersededBundleRetainedTest {

  private static final Instant DAY_1 = Instant.parse("2026-12-01T00:00:00Z");
  private static final Instant DAY_2 = Instant.parse("2027-01-01T00:00:00Z");
  private static final String V1 = policy("1.0").main("no-critical", "CRITICAL", 0).text();
  private static final String V2 = policy("2.0").main("no-critical", "CRITICAL", 1).text();

  private final PublicationFixture f = JdbcPublicationFixture.create();

  @Test
  void aSupersededBundleIsStillFetchableByDigestAndVerifies() {
    f.publisher.publish(PublicationFixture.request(V1, DAY_1));
    String oldDigest = f.catalogue.find("1.0").orElseThrow().digest();

    f.publisher.publish(PublicationFixture.request(V2, DAY_2));

    assertThat(f.catalogue.find("1.0").orElseThrow().supersededBy()).isEqualTo("2.0");
    assertThat(f.fetcher().fetch(oldDigest)).get().extracting(VerifiedBundle::text).isEqualTo(V1);
    assertThat(f.fetcher().fetch("1.0")).get().extracting(VerifiedBundle::text).isEqualTo(V1);
  }

  @Test
  void supersedingNeverChangesWhatTheOldTagPointsAt() {
    f.publisher.publish(PublicationFixture.request(V1, DAY_1));
    String oldDigest = f.catalogue.find("1.0").orElseThrow().digest();

    f.publisher.publish(PublicationFixture.request(V2, DAY_2));

    assertThat(f.registry.pull(PublicationFixture.REPOSITORY, "1.0").orElseThrow().digest())
        .isEqualTo(oldDigest);
  }

  @Test
  void theSimulatorCanStillReadASupersededBundleThroughTheRegistry() {
    f.publisher.publish(PublicationFixture.request(V1, DAY_1));
    f.publisher.publish(PublicationFixture.request(V2, DAY_2));

    var source = new RegistryPolicyBundleSource(f.catalogue, f.fetcher());

    assertThat(source.find("1.0")).isPresent();
    assertThat(source.find("2.0")).isPresent();
  }
}
