package com.debthunter.controlplane.ingest.pseudonymise;

import java.util.Base64;
import java.util.Objects;

/**
 * Production key store: the pseudonym key lives in Azure Key Vault as a base64-encoded secret.
 *
 * <p>Any failure to read it, and any secret that is not usable key material, is reported as {@link
 * KeyStoreUnavailableException} so ingest holds. Failure messages never include the secret or the
 * vault's response.
 */
public final class KeyVaultPseudonymKeyStore implements PseudonymKeyStore {

  private final KeyVaultSecretReader vault;
  private final String secretName;

  /**
   * Creates a store.
   *
   * @param vault reads secrets from the vault
   * @param secretName the secret holding the key
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public KeyVaultPseudonymKeyStore(KeyVaultSecretReader vault, String secretName) {
    this.vault = Objects.requireNonNull(vault, "vault");
    this.secretName = Objects.requireNonNull(secretName, "secretName");
  }

  @Override
  public PseudonymKey loadKey() {
    String secret;
    try {
      secret = vault.readSecret(secretName);
    } catch (RuntimeException e) {
      throw new KeyStoreUnavailableException("The pseudonym key could not be read from the vault");
    }
    try {
      return new PseudonymKey(Base64.getDecoder().decode(secret));
    } catch (RuntimeException e) {
      // IllegalArgumentException from bad base64 or a too-short key; say nothing about the value.
      throw new KeyStoreUnavailableException(
          "The vault secret is not usable pseudonym key material");
    }
  }
}
