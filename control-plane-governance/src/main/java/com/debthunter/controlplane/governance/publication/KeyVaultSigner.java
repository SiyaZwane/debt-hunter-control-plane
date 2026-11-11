package com.debthunter.controlplane.governance.publication;

/**
 * Signs bundle digests. The production implementation is backed by Azure Key Vault, so the private
 * key never reaches this process; {@link LocalSigner} stands in for tests.
 *
 * <p>Failure contract: {@code sign} either returns a complete signature or throws {@link
 * SigningException}. It never returns a signature it could not produce, and implementations never
 * fall back to a weaker key.
 */
public interface KeyVaultSigner {

  /**
   * Signs a digest.
   *
   * @param digest the bundle digest, {@code sha256:<64 hex>}
   * @return the signature
   * @throws SigningException if the key store is unavailable or refuses
   */
  Signature sign(String digest);
}
