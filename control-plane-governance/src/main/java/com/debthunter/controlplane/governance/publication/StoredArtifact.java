package com.debthunter.controlplane.governance.publication;

import java.util.Arrays;
import java.util.Objects;

/**
 * A bundle as the registry holds it: content, the digest the registry computed for it, and the
 * signature published beside it.
 *
 * @param digest the content digest, {@code sha256:<64 hex>}
 * @param content the bundle bytes
 * @param signature the signature published with it
 */
public record StoredArtifact(String digest, byte[] content, Signature signature) {

  /** Validates and copies the content. */
  public StoredArtifact {
    Objects.requireNonNull(digest, "digest");
    Objects.requireNonNull(signature, "signature");
    content = Arrays.copyOf(content, content.length);
  }

  /** A copy of the content, so the record stays immutable. */
  @Override
  public byte[] content() {
    return Arrays.copyOf(content, content.length);
  }
}
