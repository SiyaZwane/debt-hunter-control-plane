package com.debthunter.controlplane.ingest.pseudonymise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class KeyVaultPseudonymKeyStoreTest {

  private static final String SECRET_NAME = "pseudonym-key";

  @Test
  void aVaultSecretOfBase64KeyMaterialYieldsAWorkingKey() {
    String secret = Base64.getEncoder().encodeToString(Keys.sequential());
    KeyVaultPseudonymKeyStore store = new KeyVaultPseudonymKeyStore(name -> secret, SECRET_NAME);

    assertThat(
            new IdentityPseudonymiser(store, new InMemoryPseudonymDirectory())
                .pseudonymise("alice@example.com")
                .value())
        .isEqualTo("a59fc578d4cb46faab1d6eb348e7c74b33b85122d6459fdb7bf5654b333acab4");
  }

  @Test
  void theSecretIsRequestedByName() {
    String[] asked = new String[1];
    new KeyVaultPseudonymKeyStore(
            name -> {
              asked[0] = name;
              return Base64.getEncoder().encodeToString(Keys.sequential());
            },
            SECRET_NAME)
        .loadKey();

    assertThat(asked[0]).isEqualTo(SECRET_NAME);
  }

  @Test
  void anUnreachableVaultIsKeyStoreUnavailableAndSaysNothingOfTheCause() {
    KeyVaultPseudonymKeyStore store =
        new KeyVaultPseudonymKeyStore(
            name -> {
              throw new IllegalStateException("connect to vault.example failed, token=hunter2");
            },
            SECRET_NAME);

    assertThatThrownBy(store::loadKey)
        .isInstanceOf(KeyStoreUnavailableException.class)
        .hasNoCause()
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("hunter2"));
  }

  @Test
  void aSecretThatIsNotBase64IsUnavailableNotAnAccidentallyWeakKey() {
    KeyVaultPseudonymKeyStore store =
        new KeyVaultPseudonymKeyStore(name -> "!!not base64!!", SECRET_NAME);

    assertThatThrownBy(store::loadKey).isInstanceOf(KeyStoreUnavailableException.class);
  }

  @Test
  void aSecretThatDecodesToATooShortKeyIsUnavailable() {
    String shortSecret = Base64.getEncoder().encodeToString(new byte[8]);
    KeyVaultPseudonymKeyStore store =
        new KeyVaultPseudonymKeyStore(name -> shortSecret, SECRET_NAME);

    assertThatThrownBy(store::loadKey)
        .isInstanceOf(KeyStoreUnavailableException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(shortSecret));
  }
}
