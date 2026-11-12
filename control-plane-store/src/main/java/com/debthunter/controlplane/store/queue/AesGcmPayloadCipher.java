package com.debthunter.controlplane.store.queue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM, with a fresh random nonce for every payload.
 *
 * <p>Layout: one version byte, a 12-byte nonce, then the ciphertext and its authentication tag. The
 * version byte lets a future key or algorithm be introduced without guessing what an old payload
 * is. The key is supplied by configuration for now, in the same way as the pseudonymisation key; a
 * Key Vault binding replaces that.
 */
public final class AesGcmPayloadCipher implements PayloadCipher {

  private static final byte VERSION = 1;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  /**
   * Creates the cipher.
   *
   * @param keyMaterial at least 32 bytes; the first 32 are used
   * @throws IllegalArgumentException if the key is too short
   */
  public AesGcmPayloadCipher(byte[] keyMaterial) {
    if (keyMaterial == null || keyMaterial.length < 32) {
      throw new IllegalArgumentException("The payload key must be at least 32 bytes");
    }
    this.key = new SecretKeySpec(Arrays.copyOf(keyMaterial, 32), "AES");
  }

  @Override
  public byte[] seal(byte[] plaintext, String context) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(plaintext);
      byte[] sealed = new byte[1 + NONCE_BYTES + encrypted.length];
      sealed[0] = VERSION;
      System.arraycopy(nonce, 0, sealed, 1, NONCE_BYTES);
      System.arraycopy(encrypted, 0, sealed, 1 + NONCE_BYTES, encrypted.length);
      return sealed;
    } catch (GeneralSecurityException e) {
      throw new PayloadCipherException("The payload could not be sealed", e);
    }
  }

  @Override
  public byte[] open(byte[] sealed, String context) {
    if (sealed == null || sealed.length < 1 + NONCE_BYTES + TAG_BITS / 8) {
      throw new PayloadCipherException("The sealed payload is too short", null);
    }
    if (sealed[0] != VERSION) {
      throw new PayloadCipherException("Unknown payload version " + sealed[0], null);
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          key,
          new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(sealed, 1, 1 + NONCE_BYTES)));
      cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(sealed, 1 + NONCE_BYTES, sealed.length - 1 - NONCE_BYTES);
    } catch (GeneralSecurityException e) {
      throw new PayloadCipherException("The payload could not be opened", e);
    }
  }
}
