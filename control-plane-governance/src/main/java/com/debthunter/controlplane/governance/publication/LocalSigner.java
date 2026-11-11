package com.debthunter.controlplane.governance.publication;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Objects;

/**
 * An ECDSA P-256 signer and verifier held in this process, for tests and local development. It does
 * real asymmetric cryptography, so verification with only the public key behaves exactly as it
 * would against Key Vault.
 */
public final class LocalSigner implements KeyVaultSigner, SignatureVerifier {

  private static final String ALGORITHM = "SHA256withECDSA";

  private final String keyId;
  private final PrivateKey privateKey;
  private final PublicKey publicKey;

  private LocalSigner(String keyId, KeyPair pair) {
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.privateKey = pair.getPrivate();
    this.publicKey = pair.getPublic();
  }

  /**
   * Generates a fresh key pair.
   *
   * @param keyId the id the signer signs under
   * @return a signer
   */
  public static LocalSigner generate(String keyId) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(256);
      return new LocalSigner(keyId, generator.generateKeyPair());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("EC key generation unavailable", e);
    }
  }

  /** The id this signer signs under. */
  public String keyId() {
    return keyId;
  }

  /**
   * A verifier that trusts only this signer's public key, and so cannot sign.
   *
   * @return the verifier
   */
  public SignatureVerifier verifier() {
    PublicKey key = publicKey;
    String id = keyId;
    return (digest, signature) -> verifyWith(key, id, digest, signature);
  }

  @Override
  public Signature sign(String digest) {
    try {
      java.security.Signature engine = java.security.Signature.getInstance(ALGORITHM);
      engine.initSign(privateKey);
      engine.update(digest.getBytes(StandardCharsets.UTF_8));
      return new Signature(keyId, ALGORITHM, Base64.getEncoder().encodeToString(engine.sign()));
    } catch (GeneralSecurityException e) {
      throw new SigningException("Local signing failed", e);
    }
  }

  @Override
  public boolean verify(String digest, Signature signature) {
    return verifyWith(publicKey, keyId, digest, signature);
  }

  private static boolean verifyWith(
      PublicKey key, String keyId, String digest, Signature signature) {
    if (digest == null || signature == null) {
      return false;
    }
    if (!keyId.equals(signature.keyId()) || !ALGORITHM.equals(signature.algorithm())) {
      return false;
    }
    try {
      java.security.Signature engine = java.security.Signature.getInstance(ALGORITHM);
      engine.initVerify(key);
      engine.update(digest.getBytes(StandardCharsets.UTF_8));
      return engine.verify(Base64.getDecoder().decode(signature.value()));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }
}
