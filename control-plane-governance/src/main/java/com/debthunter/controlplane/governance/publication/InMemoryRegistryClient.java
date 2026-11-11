package com.debthunter.controlplane.governance.publication;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** An {@link OrasRegistryClient} that keeps artefacts in memory, honouring the full contract. */
public final class InMemoryRegistryClient implements OrasRegistryClient {

  private final Map<String, StoredArtifact> byDigest = new ConcurrentHashMap<>();
  private final Map<String, String> digestByTag = new ConcurrentHashMap<>();

  @Override
  public synchronized String push(
      String repository, String tag, byte[] content, Signature signature) {
    String digest = BundleDigest.of(content);
    String existing = digestByTag.get(key(repository, tag));
    if (existing != null && !existing.equals(digest)) {
      throw new TagConflictException(tag, existing);
    }
    byDigest.put(key(repository, digest), new StoredArtifact(digest, content, signature));
    digestByTag.put(key(repository, tag), digest);
    return digest;
  }

  @Override
  public Optional<StoredArtifact> pull(String repository, String reference) {
    String digest =
        reference.startsWith("sha256:") ? reference : digestByTag.get(key(repository, reference));
    return digest == null
        ? Optional.empty()
        : Optional.ofNullable(byDigest.get(key(repository, digest)));
  }

  private static String key(String repository, String reference) {
    return repository + "@" + reference;
  }
}
