package com.debthunter.controlplane.governance.publication;

import java.util.Optional;

/**
 * The container registry that distributes policy bundles as OCI artefacts. The production
 * implementation wraps the ORAS Java client; {@link InMemoryRegistryClient} stands in for tests.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>Content is addressed by its digest, which the registry computes itself. {@code push}
 *       returns it so the caller can check it against the digest it expected.
 *   <li>Version tags are immutable. Pushing a tag that already points at the same content is a
 *       no-op that returns the same digest; pointing it at different content throws {@link
 *       TagConflictException}.
 *   <li>There is deliberately no way to delete or overwrite an artefact here: a superseded bundle
 *       must stay retrievable, and removal is a registry-side retention job, not something this
 *       service may do.
 *   <li>Every failure is a {@link RegistryException}; a missing artefact is an empty {@code pull},
 *       not a failure.
 * </ul>
 */
public interface OrasRegistryClient {

  /**
   * Pushes a bundle and its signature, tagged with its version.
   *
   * @param repository the registry repository
   * @param tag the version tag
   * @param content the bundle bytes
   * @param signature the signature over the content's digest
   * @return the digest the registry holds the content under
   * @throws TagConflictException if the tag already points at different content
   * @throws RegistryException if the push fails
   */
  String push(String repository, String tag, byte[] content, Signature signature);

  /**
   * Fetches an artefact by tag or by digest.
   *
   * @param repository the registry repository
   * @param reference a version tag, or a {@code sha256:} digest
   * @return the artefact, or empty if the registry has none under that reference
   * @throws RegistryException if the registry cannot be asked
   */
  Optional<StoredArtifact> pull(String repository, String reference);
}
