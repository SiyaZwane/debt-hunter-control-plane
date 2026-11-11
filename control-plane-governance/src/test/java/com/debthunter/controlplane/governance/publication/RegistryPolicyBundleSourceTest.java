package com.debthunter.controlplane.governance.publication;

import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.policy.InMemoryPolicyCatalogueRepository;
import com.debthunter.controlplane.store.policy.PolicyCatalogueService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RegistryPolicyBundleSourceTest {

  private static final Instant EFFECTIVE = Instant.parse("2026-12-01T00:00:00Z");
  private final PublicationFixture f = new PublicationFixture();

  @Test
  void aPublishedVersionComesBackParsed() {
    f.publisher.publish(
        PublicationFixture.request(
            policy("1.0").main("no-critical", "CRITICAL", 0).text(), EFFECTIVE));

    Optional<com.debthunter.policy.PolicyBundle> bundle =
        new RegistryPolicyBundleSource(f.catalogue, f.fetcher()).find("1.0");

    assertThat(bundle).isPresent();
    assertThat(bundle.get().version()).isEqualTo("1.0");
  }

  @Test
  void anUnknownVersionIsNotAvailable() {
    assertThat(new RegistryPolicyBundleSource(f.catalogue, f.fetcher()).find("9.9")).isEmpty();
  }

  @Test
  void aVersionInTheCatalogueWhoseArtefactIsMissingIsNotAvailableNotAnError() {
    PolicyCatalogueService catalogue =
        new PolicyCatalogueService(new InMemoryPolicyCatalogueRepository());
    catalogue.register("1.0", "sha256:" + "1".repeat(64));

    assertThat(new RegistryPolicyBundleSource(catalogue, f.fetcher()).find("1.0")).isEmpty();
  }

  @Test
  void anArtefactThatFailsVerificationIsNotAvailable() {
    f.publisher.publish(
        PublicationFixture.request(
            policy("1.0").main("no-critical", "CRITICAL", 0).text(), EFFECTIVE));
    PolicyBundleFetcher distrustful =
        new PolicyBundleFetcher(
            f.registry,
            LocalSigner.generate("policy-key-1").verifier(),
            PublicationFixture.REPOSITORY);

    assertThat(new RegistryPolicyBundleSource(f.catalogue, distrustful).find("1.0")).isEmpty();
  }

  @Test
  void aRegistryOutageIsNotAvailableNotAnError() {
    f.publisher.publish(
        PublicationFixture.request(
            policy("1.0").main("no-critical", "CRITICAL", 0).text(), EFFECTIVE));
    OrasRegistryClient down =
        new OrasRegistryClient() {
          @Override
          public String push(String r, String t, byte[] c, Signature s) {
            throw new RegistryException("down");
          }

          @Override
          public Optional<StoredArtifact> pull(String r, String ref) {
            throw new RegistryException("down");
          }
        };

    assertThat(
            new RegistryPolicyBundleSource(
                    f.catalogue,
                    new PolicyBundleFetcher(
                        down, f.signer.verifier(), PublicationFixture.REPOSITORY))
                .find("1.0"))
        .isEmpty();
  }
}
