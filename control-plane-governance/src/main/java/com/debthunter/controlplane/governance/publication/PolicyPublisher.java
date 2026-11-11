package com.debthunter.controlplane.governance.publication;

import com.debthunter.controlplane.governance.policy.PolicyBundleValidator;
import com.debthunter.controlplane.governance.policy.ValidationReport;
import com.debthunter.controlplane.store.policy.PolicyBundleRecord;
import com.debthunter.controlplane.store.policy.PolicyCatalogueService;
import com.debthunter.policy.PolicyBundle;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes a policy bundle: validates it, signs its digest, pushes it to the registry, and only
 * then records it in the catalogue.
 *
 * <p>The order is the safety property. The catalogue is what the rest of the control plane believes
 * about "which policy applies", so it is written last, after the artefact and its signature are
 * provably in the registry under the digest the catalogue will name. A failure before that point
 * leaves the catalogue untouched; a failure at the catalogue step leaves an unreferenced artefact,
 * which is harmless and which a retry adopts, because pushing identical content is idempotent.
 *
 * <p>Publication is serialised within a process. Two control-plane instances publishing the same
 * version at once are stopped by the registry's immutable tags, not by this lock.
 */
public final class PolicyPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(PolicyPublisher.class);

  private final PolicyBundleValidator validator;
  private final KeyVaultSigner signer;
  private final SignatureVerifier verifier;
  private final OrasRegistryClient registry;
  private final PolicyCatalogueService catalogue;
  private final String repository;
  private final Clock clock;
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * Creates the publisher.
   *
   * @param validator checks a bundle before anything is signed
   * @param signer signs the digest
   * @param verifier checks the signature just produced, so a broken signer cannot publish
   * @param registry where artefacts go
   * @param catalogue the record of versions
   * @param repository the registry repository policy bundles live in
   * @param clock the time source for the ratification record
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public PolicyPublisher(
      PolicyBundleValidator validator,
      KeyVaultSigner signer,
      SignatureVerifier verifier,
      OrasRegistryClient registry,
      PolicyCatalogueService catalogue,
      String repository,
      Clock clock) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Publishes a bundle.
   *
   * @param request the bundle and its ratification
   * @return what happened; never throws for an expected failure
   */
  public PublishOutcome publish(PublishRequest request) {
    lock.lock();
    try {
      return doPublish(request);
    } finally {
      lock.unlock();
    }
  }

  private PublishOutcome doPublish(PublishRequest request) {
    byte[] content = request.bundleYaml().getBytes(StandardCharsets.UTF_8);

    ValidationReport report = validator.validate(request.bundleYaml());
    if (!report.isValid()) {
      return failed(PublishOutcome.Stage.VALIDATION, "The bundle is not valid", report, false);
    }
    PolicyBundle bundle = report.parsedBundle().orElseThrow();
    String version = bundle.version();
    String digest = BundleDigest.of(content);

    if (catalogue.find(version).isPresent()) {
      return failed(
          PublishOutcome.Stage.VERSION_CONFLICT,
          "Policy bundle version already published: " + version,
          null,
          false);
    }

    Signature signature;
    try {
      signature = signer.sign(digest);
      if (!verifier.verify(digest, signature)) {
        return failed(
            PublishOutcome.Stage.SIGNING,
            "The signer produced a signature that does not verify",
            null,
            false);
      }
    } catch (SigningException e) {
      LOG.error("Signing failed for policy bundle {}", version, e);
      return failed(PublishOutcome.Stage.SIGNING, "Signing failed: " + e.getMessage(), null, false);
    }

    try {
      String stored = registry.push(repository, version, content, signature);
      if (!digest.equals(stored)) {
        return failed(
            PublishOutcome.Stage.REGISTRY,
            "The registry stored different content than was sent (" + stored + ")",
            null,
            false);
      }
    } catch (TagConflictException e) {
      return failed(PublishOutcome.Stage.VERSION_CONFLICT, e.getMessage(), null, false);
    } catch (RegistryException e) {
      LOG.error("Registry push failed for policy bundle {}", version, e);
      return failed(
          PublishOutcome.Stage.REGISTRY, "Registry push failed: " + e.getMessage(), null, false);
    }

    try {
      catalogue.register(version, digest);
      catalogue.ratify(version, request.ratifiedBy(), clock.instant());
      catalogue.makeEffective(version, request.effectiveFrom());
      PolicyBundleRecord record = catalogue.find(version).orElseThrow();
      return new PublishOutcome.Published(record, report.warnings());
    } catch (RuntimeException e) {
      LOG.error("Catalogue update failed after pushing policy bundle {}", version, e);
      return failed(
          PublishOutcome.Stage.CATALOGUE,
          "The artefact is in the registry but the catalogue was not updated: " + e.getMessage(),
          null,
          true);
    }
  }

  private static PublishOutcome.Failed failed(
      PublishOutcome.Stage stage, String message, ValidationReport report, boolean pushed) {
    return new PublishOutcome.Failed(
        stage, message, report == null ? List.of() : report.errors(), pushed);
  }
}
