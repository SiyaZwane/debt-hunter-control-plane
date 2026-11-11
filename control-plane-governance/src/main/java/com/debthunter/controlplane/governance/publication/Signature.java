package com.debthunter.controlplane.governance.publication;

/**
 * A signature over a bundle digest.
 *
 * @param keyId which key made it, so a verifier can pick the right public key
 * @param algorithm the signature algorithm, e.g. {@code SHA256withECDSA}
 * @param value the signature, Base64 encoded
 */
public record Signature(String keyId, String algorithm, String value) {

  /** Validates the components. */
  public Signature {
    if (keyId == null || keyId.isBlank()) {
      throw new IllegalArgumentException("A signature needs a key id");
    }
    if (algorithm == null || algorithm.isBlank()) {
      throw new IllegalArgumentException("A signature needs an algorithm");
    }
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("A signature needs a value");
    }
  }
}
