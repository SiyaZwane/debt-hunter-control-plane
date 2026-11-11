package com.debthunter.controlplane.governance.publication;

import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-44: a published bundle is signed, and anyone holding the public key can fetch it by the
 * digest the catalogue names and prove it is the bundle that was published.
 */
@Tag("integration")
class CpAc44_SignedBundleAvailableByDigestTest {

  private static final Instant EFFECTIVE = Instant.parse("2026-12-01T00:00:00Z");
  private static final String V1 = policy("1.0").main("no-critical", "CRITICAL", 0).text();

  private final PublicationFixture f = JdbcPublicationFixture.create();

  @Test
  void theDigestInTheCatalogueFetchesTheSignedBundleFromTheRegistry() {
    f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE));
    String digest = f.catalogue.find("1.0").orElseThrow().digest();

    VerifiedBundle fetched = f.fetcher().fetch(digest).orElseThrow();

    assertThat(fetched.text()).isEqualTo(V1);
    assertThat(fetched.digest()).isEqualTo(BundleDigest.of(V1.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void aVerifierThatCannotSignIsEnoughToTrustTheBundle() {
    f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE));
    String digest = f.catalogue.find("1.0").orElseThrow().digest();
    SignatureVerifier publicKeyOnly = f.signer.verifier();

    assertThat(publicKeyOnly).isNotInstanceOf(KeyVaultSigner.class);
    assertThat(
            new PolicyBundleFetcher(f.registry, publicKeyOnly, PublicationFixture.REPOSITORY)
                .fetch(digest))
        .isPresent();
  }

  @Test
  void aBundleSignedWithAnyOtherKeyIsNotTrusted() {
    f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE));
    String digest = f.catalogue.find("1.0").orElseThrow().digest();
    PolicyBundleFetcher wrongKey =
        new PolicyBundleFetcher(
            f.registry,
            LocalSigner.generate("policy-key-1").verifier(),
            PublicationFixture.REPOSITORY);

    assertThatThrownBy(() -> wrongKey.fetch(digest)).isInstanceOf(UntrustedBundleException.class);
  }
}
