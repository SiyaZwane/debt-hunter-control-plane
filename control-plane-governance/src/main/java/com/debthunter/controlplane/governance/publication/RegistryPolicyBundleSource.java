package com.debthunter.controlplane.governance.publication;

import com.debthunter.controlplane.governance.simulation.PolicyBundleSource;
import com.debthunter.controlplane.store.policy.PolicyBundleRecord;
import com.debthunter.controlplane.store.policy.PolicyCatalogueService;
import com.debthunter.policy.PolicyBundle;
import com.debthunter.policy.PolicyBundleParser;
import com.debthunter.policy.PolicyParseException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supplies the simulator with the content of a published bundle: the catalogue names the digest,
 * the registry holds the bytes, and the bytes are verified before they are believed. A bundle that
 * cannot be fetched or verified is reported as "not available", which the simulator already knows
 * how to handle.
 */
public final class RegistryPolicyBundleSource implements PolicyBundleSource {

  private static final Logger LOG = LoggerFactory.getLogger(RegistryPolicyBundleSource.class);

  private final PolicyCatalogueService catalogue;
  private final PolicyBundleFetcher fetcher;

  /**
   * Creates the source.
   *
   * @param catalogue which digest each version has
   * @param fetcher fetches and verifies by digest
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public RegistryPolicyBundleSource(PolicyCatalogueService catalogue, PolicyBundleFetcher fetcher) {
    this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
    this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
  }

  @Override
  public Optional<PolicyBundle> find(String version) {
    Optional<PolicyBundleRecord> record = catalogue.find(version);
    if (record.isEmpty()) {
      return Optional.empty();
    }
    try {
      return fetcher
          .fetch(record.get().digest())
          .map(verified -> new PolicyBundleParser().parse(verified.text()));
    } catch (UntrustedBundleException | RegistryException | PolicyParseException e) {
      LOG.warn("Policy bundle {} is not available: {}", version, e.getMessage());
      return Optional.empty();
    }
  }
}
