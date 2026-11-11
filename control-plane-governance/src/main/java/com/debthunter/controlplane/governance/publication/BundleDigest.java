package com.debthunter.controlplane.governance.publication;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes the content digest of a bundle, in the form the catalogue and registry use. */
public final class BundleDigest {

  private BundleDigest() {}

  /**
   * Digests content.
   *
   * @param content the bytes
   * @return {@code sha256:<64 hex>}
   */
  public static String of(byte[] content) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
