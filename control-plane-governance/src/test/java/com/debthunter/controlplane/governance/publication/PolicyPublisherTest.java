package com.debthunter.controlplane.governance.publication;

import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.debthunter.controlplane.governance.publication.PublishOutcome.Failed;
import com.debthunter.controlplane.governance.publication.PublishOutcome.Published;
import com.debthunter.controlplane.governance.publication.PublishOutcome.Stage;
import com.debthunter.controlplane.store.policy.InMemoryPolicyCatalogueRepository;
import com.debthunter.controlplane.store.policy.PolicyBundleRecord;
import com.debthunter.controlplane.store.policy.PolicyCatalogueRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PolicyPublisherTest {

  private static final Instant EFFECTIVE = Instant.parse("2026-12-01T00:00:00Z");
  private static final String V1 = policy("1.0").main("no-critical", "CRITICAL", 0).text();
  private static final String V2 = policy("2.0").main("no-critical", "CRITICAL", 0).text();

  private static Failed failed(PublishOutcome outcome) {
    assertThat(outcome).isInstanceOf(Failed.class);
    return (Failed) outcome;
  }

  // ---- CP-AC-44: signed, retrievable by digest ----------------------------------------------

  @Test
  void aPublishedBundleIsInTheRegistryUnderItsDigestWithAVerifiableSignature() {
    PublicationFixture f = new PublicationFixture();

    Published published =
        (Published) f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE));

    String digest = BundleDigest.of(V1.getBytes(StandardCharsets.UTF_8));
    assertThat(published.record().digest()).isEqualTo(digest);
    StoredArtifact stored = f.registry.pull(PublicationFixture.REPOSITORY, digest).orElseThrow();
    assertThat(new String(stored.content(), StandardCharsets.UTF_8)).isEqualTo(V1);
    assertThat(f.signer.verifier().verify(digest, stored.signature())).isTrue();
  }

  @Test
  void theVersionTagResolvesToTheSameArtefactAsTheDigest() {
    PublicationFixture f = new PublicationFixture();
    f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE));

    StoredArtifact byTag = f.registry.pull(PublicationFixture.REPOSITORY, "1.0").orElseThrow();
    StoredArtifact byDigest =
        f.registry.pull(PublicationFixture.REPOSITORY, byTag.digest()).orElseThrow();

    assertThat(byTag.content()).isEqualTo(byDigest.content());
  }

  // ---- CP-AC-45: catalogue entry ------------------------------------------------------------

  @Test
  void theCatalogueEntryRecordsDigestRatificationAndEffectiveDate() {
    PublicationFixture f = new PublicationFixture();

    f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE));

    PolicyBundleRecord record = f.catalogue.find("1.0").orElseThrow();
    assertThat(record.digest()).isEqualTo(BundleDigest.of(V1.getBytes(StandardCharsets.UTF_8)));
    assertThat(record.ratifiedBy()).isEqualTo("policy-board");
    assertThat(record.ratifiedAt()).isEqualTo(PublicationFixture.NOW);
    assertThat(record.effectiveFrom()).isEqualTo(EFFECTIVE);
    assertThat(record.supersededBy()).isNull();
  }

  @Test
  void publishingANewerVersionMarksTheOlderOneSuperseded() {
    PublicationFixture f = new PublicationFixture();
    f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE));

    f.publisher.publish(PublicationFixture.request(V2, EFFECTIVE.plusSeconds(86_400)));

    assertThat(f.catalogue.find("1.0").orElseThrow().supersededBy()).isEqualTo("2.0");
    assertThat(f.catalogue.find("2.0").orElseThrow().supersededBy()).isNull();
  }

  @Test
  void warningsAreReportedButDoNotBlockPublication() {
    PublicationFixture f = new PublicationFixture();
    String noRules = policy("3.0").text();

    PublishOutcome outcome = f.publisher.publish(PublicationFixture.request(noRules, EFFECTIVE));

    assertThat(outcome).isInstanceOf(Published.class);
    assertThat(((Published) outcome).warnings()).isNotEmpty();
  }

  // ---- refusals leave nothing behind --------------------------------------------------------

  @Test
  void anInvalidBundleIsRefusedBeforeAnythingIsSignedPushedOrRecorded() {
    AtomicInteger signed = new AtomicInteger();
    PublicationFixture f =
        new PublicationFixture(
            new InMemoryPolicyCatalogueRepository(),
            new InMemoryRegistryClient(),
            digest -> {
              signed.incrementAndGet();
              throw new SigningException("must not be called", null);
            });

    Failed failed =
        failed(f.publisher.publish(PublicationFixture.request("not: [valid", EFFECTIVE)));

    assertThat(failed.stage()).isEqualTo(Stage.VALIDATION);
    assertThat(failed.issues()).isNotEmpty();
    assertThat(signed).hasValue(0);
    assertThat(f.catalogue.effectiveAt(Instant.MAX)).isEmpty();
  }

  @Test
  void aVersionAlreadyInTheCatalogueIsRefusedAndTheOriginalIsUntouched() {
    PublicationFixture f = new PublicationFixture();
    f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE));
    String changed = policy("1.0").main("no-critical", "CRITICAL", 5).text();

    Failed failed = failed(f.publisher.publish(PublicationFixture.request(changed, EFFECTIVE)));

    assertThat(failed.stage()).isEqualTo(Stage.VERSION_CONFLICT);
    assertThat(f.catalogue.find("1.0").orElseThrow().digest())
        .isEqualTo(BundleDigest.of(V1.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void republishingIdenticalContentUnderACataloguedVersionIsAConflictNotAHalfDoneRetry() {
    PublicationFixture f = new PublicationFixture();
    f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE));

    Failed failed = failed(f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE)));

    assertThat(failed.stage()).isEqualTo(Stage.VERSION_CONFLICT);
  }

  @Test
  void aRegistryTagHeldByOtherContentIsAConflictAndNothingIsCatalogued() {
    PublicationFixture f = new PublicationFixture();
    f.registry.push(
        PublicationFixture.REPOSITORY,
        "1.0",
        "something else".getBytes(StandardCharsets.UTF_8),
        f.signer.sign("sha256:" + "0".repeat(64)));

    Failed failed = failed(f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE)));

    assertThat(failed.stage()).isEqualTo(Stage.VERSION_CONFLICT);
    assertThat(f.catalogue.find("1.0")).isEmpty();
  }

  // ---- signing failures ---------------------------------------------------------------------

  @Test
  void whenTheKeyStoreIsDownNothingIsPushedOrRecorded() {
    InMemoryRegistryClient registry = new InMemoryRegistryClient();
    PublicationFixture f =
        new PublicationFixture(
            new InMemoryPolicyCatalogueRepository(),
            registry,
            digest -> {
              throw new SigningException("key vault unavailable", null);
            });

    Failed failed = failed(f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE)));

    assertThat(failed.stage()).isEqualTo(Stage.SIGNING);
    assertThat(registry.pull(PublicationFixture.REPOSITORY, "1.0")).isEmpty();
    assertThat(f.catalogue.find("1.0")).isEmpty();
  }

  @Test
  void aSignatureThatDoesNotVerifyIsNeverPublished() {
    InMemoryRegistryClient registry = new InMemoryRegistryClient();
    LocalSigner stranger = LocalSigner.generate("policy-key-1");
    PublicationFixture f =
        new PublicationFixture(new InMemoryPolicyCatalogueRepository(), registry, stranger);

    Failed failed = failed(f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE)));

    assertThat(failed.stage()).isEqualTo(Stage.SIGNING);
    assertThat(registry.pull(PublicationFixture.REPOSITORY, "1.0")).isEmpty();
    assertThat(f.catalogue.find("1.0")).isEmpty();
  }

  // ---- registry failures --------------------------------------------------------------------

  private static OrasRegistryClient failingRegistry() {
    return new OrasRegistryClient() {
      @Override
      public String push(String repository, String tag, byte[] content, Signature signature) {
        throw new RegistryException("registry unreachable");
      }

      @Override
      public Optional<StoredArtifact> pull(String repository, String reference) {
        throw new RegistryException("registry unreachable");
      }
    };
  }

  @Test
  void aRegistryFailureLeavesTheCatalogueUntouched() {
    PublicationFixture f =
        new PublicationFixture(new InMemoryPolicyCatalogueRepository(), failingRegistry(), null);

    Failed failed = failed(f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE)));

    assertThat(failed.stage()).isEqualTo(Stage.REGISTRY);
    assertThat(failed.artefactInRegistry()).isFalse();
    assertThat(f.catalogue.find("1.0")).isEmpty();
  }

  @Test
  void aRegistryThatStoresDifferentContentIsNotTrusted() {
    OrasRegistryClient lying =
        new OrasRegistryClient() {
          @Override
          public String push(String repository, String tag, byte[] content, Signature signature) {
            return "sha256:" + "a".repeat(64);
          }

          @Override
          public Optional<StoredArtifact> pull(String repository, String reference) {
            return Optional.empty();
          }
        };
    PublicationFixture f =
        new PublicationFixture(new InMemoryPolicyCatalogueRepository(), lying, null);

    Failed failed = failed(f.publisher.publish(PublicationFixture.request(V1, EFFECTIVE)));

    assertThat(failed.stage()).isEqualTo(Stage.REGISTRY);
    assertThat(f.catalogue.find("1.0")).isEmpty();
  }

  // ---- catalogue failures, and the retry that heals them ------------------------------------

  /** A catalogue whose writes fail until told otherwise. */
  private static final class FlakyCatalogue implements PolicyCatalogueRepository {
    private final InMemoryPolicyCatalogueRepository delegate =
        new InMemoryPolicyCatalogueRepository();
    private volatile boolean failing = true;

    @Override
    public void save(PolicyBundleRecord bundle) {
      if (failing) {
        throw new IllegalStateException("database down");
      }
      delegate.save(bundle);
    }

    @Override
    public Optional<PolicyBundleRecord> find(String version) {
      return delegate.find(version);
    }

    @Override
    public List<PolicyBundleRecord> all() {
      return delegate.all();
    }
  }

  @Test
  void aCatalogueFailureAfterThePushIsReportedAsSafeToRetryAndARetryCompletes() {
    FlakyCatalogue catalogue = new FlakyCatalogue();
    PublicationFixture f = new PublicationFixture(catalogue);
    OrasRegistryClient registry = f.registry;
    PolicyPublisher publisher =
        new PolicyPublisher(
            new com.debthunter.controlplane.governance.policy.PolicyBundleValidator(),
            f.signer,
            f.signer.verifier(),
            registry,
            f.catalogue,
            PublicationFixture.REPOSITORY,
            java.time.Clock.fixed(PublicationFixture.NOW, java.time.ZoneOffset.UTC));

    Failed first = failed(publisher.publish(PublicationFixture.request(V1, EFFECTIVE)));

    assertThat(first.stage()).isEqualTo(Stage.CATALOGUE);
    assertThat(first.artefactInRegistry()).isTrue();
    assertThat(f.catalogue.find("1.0")).isEmpty();

    catalogue.failing = false;
    assertThat(publisher.publish(PublicationFixture.request(V1, EFFECTIVE)))
        .isInstanceOf(Published.class);
    assertThat(f.catalogue.find("1.0").orElseThrow().isRatified()).isTrue();
  }

  // ---- argument checks ----------------------------------------------------------------------

  @Test
  void aRequestWithoutARatifierOrDateIsRejectedAtConstruction() {
    assertThatIllegalArgumentException().isThrownBy(() -> new PublishRequest(V1, " ", EFFECTIVE));
    assertThatIllegalArgumentException().isThrownBy(() -> new PublishRequest(V1, "board", null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PublishRequest(null, "board", EFFECTIVE));
  }
}
