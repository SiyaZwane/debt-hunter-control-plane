package com.debthunter.controlplane.ingest.pseudonymise;

/**
 * The one operation needed from Azure Key Vault: read a secret's value. The production binding to
 * the Azure SDK implements this; tests use a fake.
 */
@FunctionalInterface
public interface KeyVaultSecretReader {

  /**
   * Reads a secret.
   *
   * @param name the secret's name
   * @return its value, base64-encoded key material
   * @throws RuntimeException if the vault cannot be reached or the secret cannot be read
   */
  String readSecret(String name);
}
