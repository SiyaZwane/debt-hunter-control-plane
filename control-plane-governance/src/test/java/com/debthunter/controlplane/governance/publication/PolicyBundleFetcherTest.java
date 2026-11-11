package com.debthunter.controlplane.governance.publication;

import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyBundleFetcherTest {

  private static final Instant EFFECTIVE = Instant.parse("2026-12-01T00:00:00Z");
  private static final String V1 = policy("1.0").main("no-critical", "CRITICAL", 0).text();

  private final PublicationFixture f = new PublicationFixture();

  private String publishV1() {
    f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE));
    return f.catalogue.find("1.0").orElseThrow().digest();
  }

  @Test
  void aPublishedBundleIsFetchedByDigestAndVerified() {
    String digest = publishV1();

    VerifiedBundle bundle = f.fetcher().fetch(digest).orElseThrow();

    assertThat(bundle.text()).isEqualTo(V1);
    assertThat(bundle.digest()).isEqualTo(digest);
  }

  @Test
  void aBundleNobodyPublishedIsEmptyNotAnError() {
    assertThat(f.fetcher().fetch("9.9")).isEmpty();
  }

  @Test
  void aSignatureFromAnotherKeyIsRefused() {
    String digest = publishV1();
    PolicyBundleFetcher distrustful =
        new PolicyBundleFetcher(
            f.registry,
            LocalSigner.generate("policy-key-1").verifier(),
            PublicationFixture.REPOSITORY);

    assertThatThrownBy(() -> distrustful.fetch(digest))
        .isInstanceOf(UntrustedBundleException.class);
  }

  /** A registry that hands back whatever it is told to, whatever was asked for. */
  private PolicyBundleFetcher fetcherOver(StoredArtifact served) {
    OrasRegistryClient tampering =
        new OrasRegistryClient() {
          @Override
          public String push(String r, String t, byte[] c, Signature s) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Optional<StoredArtifact> pull(String r, String ref) {
            return Optional.of(served);
          }
        };
    return new PolicyBundleFetcher(tampering, f.signer.verifier(), PublicationFixture.REPOSITORY);
  }

  @Test
  void contentThatDoesNotMatchItsClaimedDigestIsRefused() {
    String digest = publishV1();
    StoredArtifact genuine = f.registry.pull(PublicationFixture.REPOSITORY, digest).orElseThrow();
    StoredArtifact tampered =
        new StoredArtifact(
            digest,
            "version: \"1.0\"\n# tampered\n".getBytes(StandardCharsets.UTF_8),
            genuine.signature());

    assertThatThrownBy(() -> fetcherOver(tampered).fetch(digest))
        .isInstanceOf(UntrustedBundleException.class)
        .hasMessageContaining("digest");
  }

  @Test
  void aDifferentGenuineBundleServedInPlaceOfTheOneAskedForIsRefused() {
    String asked = publishV1();
    byte[] other = "version: \"2.0\"\n".getBytes(StandardCharsets.UTF_8);
    String otherDigest = BundleDigest.of(other);
    StoredArtifact swapped = new StoredArtifact(otherDigest, other, f.signer.sign(otherDigest));

    assertThatThrownBy(() -> fetcherOver(swapped).fetch(asked))
        .isInstanceOf(UntrustedBundleException.class)
        .hasMessageContaining("Asked for");
  }

  @Test
  void anUnsignedOrBadlySignedBundleIsRefused() {
    String digest = publishV1();
    StoredArtifact genuine = f.registry.pull(PublicationFixture.REPOSITORY, digest).orElseThrow();
    StoredArtifact bad =
        new StoredArtifact(
            digest, genuine.content(), new Signature("policy-key-1", "SHA256withECDSA", "AAAA"));

    assertThatThrownBy(() -> fetcherOver(bad).fetch(digest))
        .isInstanceOf(UntrustedBundleException.class);
  }

  @Test
  void fetchToWritesTheVerifiedBundleToAFile(@TempDir Path dir) throws IOException {
    String digest = publishV1();
    Path target = dir.resolve("policy.yml");

    f.fetcher().fetchTo(digest, target);

    assertThat(Files.readString(target)).isEqualTo(V1);
  }

  @Test
  void fetchToWritesNothingWhenVerificationFails(@TempDir Path dir) {
    String digest = publishV1();
    StoredArtifact genuine = f.registry.pull(PublicationFixture.REPOSITORY, digest).orElseThrow();
    StoredArtifact bad =
        new StoredArtifact(
            digest, genuine.content(), new Signature("policy-key-1", "SHA256withECDSA", "AAAA"));
    Path target = dir.resolve("policy.yml");

    assertThatThrownBy(() -> fetcherOver(bad).fetchTo(digest, target))
        .isInstanceOf(UntrustedBundleException.class);
    assertThat(target).doesNotExist();
  }

  @Test
  void fetchToWritesNothingWhenTheRegistryHasNoSuchBundle(@TempDir Path dir) {
    Path target = dir.resolve("policy.yml");

    assertThat(f.fetcher().fetchTo("9.9", target)).isEmpty();
    assertThat(target).doesNotExist();
  }
}
