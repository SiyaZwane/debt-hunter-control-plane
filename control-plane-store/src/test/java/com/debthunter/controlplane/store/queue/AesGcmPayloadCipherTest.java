package com.debthunter.controlplane.store.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AesGcmPayloadCipherTest {

  private static byte[] key(int fill) {
    byte[] k = new byte[32];
    Arrays.fill(k, (byte) fill);
    return k;
  }

  private final AesGcmPayloadCipher cipher = new AesGcmPayloadCipher(key(7));
  private final byte[] plain = "alice@example.com wrote this".getBytes(StandardCharsets.UTF_8);

  @Test
  void whatIsSealedOpensToTheSameBytes() {
    assertThat(cipher.open(cipher.seal(plain, "run-intake:acme/a"), "run-intake:acme/a"))
        .isEqualTo(plain);
  }

  @Test
  void theSealedBytesDoNotContainThePlaintext() {
    String sealed = new String(cipher.seal(plain, "ctx"), StandardCharsets.ISO_8859_1);

    assertThat(sealed).doesNotContain("alice").doesNotContain("example.com");
  }

  @Test
  void everySealingUsesAFreshNonce() {
    assertThat(cipher.seal(plain, "ctx")).isNotEqualTo(cipher.seal(plain, "ctx"));
  }

  @Test
  void aPayloadMovedToAnotherContextWillNotOpen() {
    byte[] sealed = cipher.seal(plain, "run-intake:acme/a");

    assertThatThrownBy(() -> cipher.open(sealed, "run-intake:acme/b"))
        .isInstanceOf(PayloadCipherException.class);
  }

  @Test
  void aDamagedPayloadWillNotOpen() {
    byte[] sealed = cipher.seal(plain, "ctx");
    sealed[sealed.length - 1] ^= 1;

    assertThatThrownBy(() -> cipher.open(sealed, "ctx")).isInstanceOf(PayloadCipherException.class);
  }

  @Test
  void aDifferentKeyWillNotOpenIt() {
    byte[] sealed = cipher.seal(plain, "ctx");

    assertThatThrownBy(() -> new AesGcmPayloadCipher(key(8)).open(sealed, "ctx"))
        .isInstanceOf(PayloadCipherException.class);
  }

  @Test
  void anUnknownVersionOrATruncatedPayloadWillNotOpen() {
    byte[] sealed = cipher.seal(plain, "ctx");
    byte[] otherVersion = sealed.clone();
    otherVersion[0] = 9;

    assertThatThrownBy(() -> cipher.open(otherVersion, "ctx")).hasMessageContaining("version");
    assertThatThrownBy(() -> cipher.open(new byte[5], "ctx")).hasMessageContaining("short");
    assertThatThrownBy(() -> cipher.open(null, "ctx")).isInstanceOf(PayloadCipherException.class);
  }

  @Test
  void aShortKeyIsRefused() {
    assertThatIllegalArgumentException().isThrownBy(() -> new AesGcmPayloadCipher(new byte[16]));
    assertThatIllegalArgumentException().isThrownBy(() -> new AesGcmPayloadCipher(null));
  }

  @Test
  void anEmptyPayloadRoundTrips() {
    assertThat(cipher.open(cipher.seal(new byte[0], "ctx"), "ctx")).isEmpty();
  }
}
