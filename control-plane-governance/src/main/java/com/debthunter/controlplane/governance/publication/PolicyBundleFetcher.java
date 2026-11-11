package com.debthunter.controlplane.governance.publication;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Fetches a policy bundle from the registry and checks it before anyone relies on it. This is the
 * consuming side: it needs a registry and a verifier, and nothing of the control plane, so a
 * pipeline can obtain its policy with the control plane switched off.
 *
 * <p>Nothing unverified is ever returned or written: content whose digest does not match what was
 * asked for, or whose signature does not verify, is refused with {@link UntrustedBundleException}.
 */
public final class PolicyBundleFetcher {

  private final OrasRegistryClient registry;
  private final SignatureVerifier verifier;
  private final String repository;

  /**
   * Creates the fetcher.
   *
   * @param registry where bundles are held
   * @param verifier the trusted public key
   * @param repository the registry repository policy bundles live in
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public PolicyBundleFetcher(
      OrasRegistryClient registry, SignatureVerifier verifier, String repository) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /**
   * Fetches and verifies a bundle.
   *
   * @param reference a version tag or a {@code sha256:} digest. Prefer a digest: a tag is only as
   *     trustworthy as the registry's tag immutability.
   * @return the verified bundle, or empty if the registry has nothing under the reference
   * @throws UntrustedBundleException if what came back does not verify
   * @throws RegistryException if the registry cannot be reached
   */
  public Optional<VerifiedBundle> fetch(String reference) {
    Optional<StoredArtifact> found = registry.pull(repository, reference);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    StoredArtifact artifact = found.get();
    byte[] content = artifact.content();
    String actual = BundleDigest.of(content);
    if (!actual.equals(artifact.digest())) {
      throw new UntrustedBundleException(
          "Content does not match its digest " + artifact.digest() + " (actual " + actual + ")");
    }
    if (reference.startsWith("sha256:") && !actual.equals(reference)) {
      throw new UntrustedBundleException(
          "Asked for " + reference + " but the registry returned " + actual);
    }
    if (!verifier.verify(actual, artifact.signature())) {
      throw new UntrustedBundleException("The signature on " + actual + " does not verify");
    }
    return Optional.of(new VerifiedBundle(actual, content));
  }

  /**
   * Fetches, verifies, and writes a bundle to a file, ready to pass to the data plane as {@code
   * --policy}. The file is only created if verification succeeded.
   *
   * @param reference a version tag or digest
   * @param target where to write
   * @return the verified bundle, or empty if the registry has none
   */
  public Optional<VerifiedBundle> fetchTo(String reference, Path target) {
    Optional<VerifiedBundle> bundle = fetch(reference);
    if (bundle.isPresent()) {
      try {
        Files.write(target, bundle.get().content());
      } catch (IOException e) {
        throw new UncheckedIOException("Could not write " + target, e);
      }
    }
    return bundle;
  }
}
