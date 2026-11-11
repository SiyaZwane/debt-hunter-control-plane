package com.debthunter.controlplane.ingest.pseudonymise;

/** Key material for tests. */
final class Keys {

  private Keys() {}

  /** Bytes 0..31, so the expected pseudonyms can be computed independently. */
  static byte[] sequential() {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) i;
    }
    return key;
  }

  static byte[] filled(int value) {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) value);
    return key;
  }
}
