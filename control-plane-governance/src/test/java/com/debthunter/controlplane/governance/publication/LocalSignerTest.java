package com.debthunter.controlplane.governance.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class LocalSignerTest {

  private static final String DIGEST = "sha256:" + "ab".repeat(32);

  @Test
  void aSignatureVerifiesForTheDigestItSigned() {
    LocalSigner signer = LocalSigner.generate("k1");

    assertThat(signer.verify(DIGEST, signer.sign(DIGEST))).isTrue();
  }

  @Test
  void aSignatureDoesNotVerifyForADifferentDigest() {
    LocalSigner signer = LocalSigner.generate("k1");

    assertThat(signer.verify("sha256:" + "cd".repeat(32), signer.sign(DIGEST))).isFalse();
  }

  @Test
  void anotherKeyWithTheSameIdCannotForgeASignature() {
    LocalSigner genuine = LocalSigner.generate("k1");
    LocalSigner forger = LocalSigner.generate("k1");

    assertThat(genuine.verifier().verify(DIGEST, forger.sign(DIGEST))).isFalse();
  }

  @Test
  void aSignatureUnderAnUnknownKeyIdIsRejected() {
    LocalSigner a = LocalSigner.generate("k1");
    LocalSigner b = LocalSigner.generate("k2");

    assertThat(a.verifier().verify(DIGEST, b.sign(DIGEST))).isFalse();
  }

  @Test
  void aGenuineSignatureRelabelledWithAnotherKeyIdIsRejected() {
    LocalSigner signer = LocalSigner.generate("k1");
    Signature genuine = signer.sign(DIGEST);

    assertThat(signer.verify(DIGEST, new Signature("k2", genuine.algorithm(), genuine.value())))
        .isFalse();
    assertThat(signer.verify(DIGEST, new Signature("k1", "SHA1withECDSA", genuine.value())))
        .isFalse();
  }

  @Test
  void aMalformedOrMissingSignatureIsRejectedNotThrown() {
    LocalSigner signer = LocalSigner.generate("k1");

    assertThat(signer.verify(DIGEST, new Signature("k1", "SHA256withECDSA", "!!not base64!!")))
        .isFalse();
    assertThat(signer.verify(DIGEST, new Signature("k1", "SHA256withECDSA", "AAAA"))).isFalse();
    assertThat(signer.verify(DIGEST, null)).isFalse();
    assertThat(signer.verify(null, signer.sign(DIGEST))).isFalse();
  }

  @Test
  void aSignatureNeedsAKeyAlgorithmAndValue() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Signature("", "a", "v"));
    assertThatIllegalArgumentException().isThrownBy(() -> new Signature("k", null, "v"));
    assertThatIllegalArgumentException().isThrownBy(() -> new Signature("k", "a", " "));
  }
}
