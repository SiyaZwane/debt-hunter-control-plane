package com.debthunter.controlplane.ingest.pseudonymise;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The per-deployment secret that makes pseudonyms unguessable. It can compute with itself but
 * cannot be read back out, and it never appears in a log line or a stack trace.
 */
public final class PseudonymKey {

  /** The shortest key accepted: 256 bits, the size of the HMAC-SHA256 output. */
  public static final int MIN_LENGTH_BYTES = 32;

  private static final String ALGORITHM = "HmacSHA256";

  private final SecretKeySpec key;

  /**
   * Wraps key material.
   *
   * @param material at least {@value #MIN_LENGTH_BYTES} bytes; copied
   * @throws IllegalArgumentException if the key is too short to be safe
   */
  public PseudonymKey(byte[] material) {
    if (material == null || material.length < MIN_LENGTH_BYTES) {
      throw new IllegalArgumentException(
          "The pseudonym key must be at least " + MIN_LENGTH_BYTES + " bytes");
    }
    this.key = new SecretKeySpec(material.clone(), ALGORITHM);
  }

  /**
   * Computes HMAC-SHA256 over data using this key.
   *
   * @param data the bytes to authenticate
   * @return the 32-byte MAC
   */
  byte[] hmac(byte[] data) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(data);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String toString() {
    return "PseudonymKey[redacted]";
  }
}
