package com.debthunter.controlplane.governance.publication;

/**
 * Checks signatures. Separate from {@link KeyVaultSigner} because a consumer of bundles needs the
 * ability to verify and must never need the ability to sign.
 */
public interface SignatureVerifier {

  /**
   * Whether a signature is valid for a digest.
   *
   * @param digest the bundle digest
   * @param signature the signature presented with it
   * @return true only if it verifies against a trusted key; false for any other reason, including
   *     an unknown key id or a malformed signature
   */
  boolean verify(String digest, Signature signature);
}
