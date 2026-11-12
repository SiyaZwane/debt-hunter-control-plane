package com.debthunter.controlplane.store.queue;

/**
 * Encrypts what is queued, so that a payload that holds sensitive data (the raw bundle, with
 * contributor identities) is never readable in the database.
 *
 * <p>The {@code context} is authenticated along with the payload, so a sealed payload cannot be
 * moved to another kind of work or another key and still open.
 */
public interface PayloadCipher {

  /**
   * Encrypts.
   *
   * @param plaintext what to protect
   * @param context what it belongs to (for example the queue kind and key)
   * @return the sealed bytes
   */
  byte[] seal(byte[] plaintext, String context);

  /**
   * Decrypts.
   *
   * @param sealed bytes made by {@link #seal}
   * @param context the same context it was sealed under
   * @return the plaintext
   * @throws PayloadCipherException if it cannot be opened
   */
  byte[] open(byte[] sealed, String context);
}
