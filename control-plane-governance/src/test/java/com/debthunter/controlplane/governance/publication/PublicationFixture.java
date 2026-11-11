package com.debthunter.controlplane.governance.publication;

import com.debthunter.controlplane.governance.policy.PolicyBundleValidator;
import com.debthunter.controlplane.store.policy.InMemoryPolicyCatalogueRepository;
import com.debthunter.controlplane.store.policy.PolicyCatalogueRepository;
import com.debthunter.controlplane.store.policy.PolicyCatalogueService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** A publisher wired to in-memory collaborators (or a supplied catalogue), plus its parts. */
public final class PublicationFixture {

  public static final String REPOSITORY = "policy/bundles";
  public static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");

  public final LocalSigner signer = LocalSigner.generate("policy-key-1");
  public final OrasRegistryClient registry;
  public final PolicyCatalogueService catalogue;
  public final PolicyPublisher publisher;

  public PublicationFixture() {
    this(new InMemoryPolicyCatalogueRepository());
  }

  public PublicationFixture(PolicyCatalogueRepository repository) {
    this(repository, new InMemoryRegistryClient(), null);
  }

  /** Builds a fixture over the given pieces; a null signer means the fixture's own. */
  public PublicationFixture(
      PolicyCatalogueRepository repository, OrasRegistryClient client, KeyVaultSigner override) {
    this.registry = client;
    this.catalogue = new PolicyCatalogueService(repository);
    this.publisher =
        new PolicyPublisher(
            new PolicyBundleValidator(),
            override == null ? signer : override,
            signer.verifier(),
            client,
            catalogue,
            REPOSITORY,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  public PolicyBundleFetcher fetcher() {
    return new PolicyBundleFetcher(registry, signer.verifier(), REPOSITORY);
  }

  public static PublishRequest request(String yaml, Instant effectiveFrom) {
    return new PublishRequest(yaml, "policy-board", effectiveFrom);
  }
}
