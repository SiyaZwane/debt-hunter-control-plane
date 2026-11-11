package com.debthunter.controlplane.ingest.pseudonymise;

/** A {@link PseudonymKeyStore} over a key already in memory, for tests and local development. */
public final class InMemoryPseudonymKeyStore implements PseudonymKeyStore {

  private final PseudonymKey key;

  /**
   * Creates a store.
   *
   * @param keyMaterial at least {@value PseudonymKey#MIN_LENGTH_BYTES} bytes
   */
  public InMemoryPseudonymKeyStore(byte[] keyMaterial) {
    this.key = new PseudonymKey(keyMaterial);
  }

  @Override
  public PseudonymKey loadKey() {
    return key;
  }
}
