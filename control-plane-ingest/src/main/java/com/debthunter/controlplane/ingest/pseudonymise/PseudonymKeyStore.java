package com.debthunter.controlplane.ingest.pseudonymise;

/** Where the pseudonym key comes from. */
public interface PseudonymKeyStore {

  /**
   * Fetches the key.
   *
   * @return the key
   * @throws KeyStoreUnavailableException if it cannot be obtained right now; the caller must hold,
   *     not proceed without it
   */
  PseudonymKey loadKey();
}
