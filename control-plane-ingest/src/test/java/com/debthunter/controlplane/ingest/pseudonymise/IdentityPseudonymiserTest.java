package com.debthunter.controlplane.ingest.pseudonymise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory;
import com.debthunter.controlplane.store.identity.Pseudonym;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdentityPseudonymiserTest {

  private final InMemoryPseudonymDirectory directory = new InMemoryPseudonymDirectory();

  private IdentityPseudonymiser with(byte[] key) {
    return new IdentityPseudonymiser(new InMemoryPseudonymKeyStore(key), directory);
  }

  @Test
  void theAlgorithmIsHmacSha256MatchingAnIndependentComputation() {
    // Expected value computed outside Java: hmac.new(bytes(range(32)), b"alice@example.com",
    // sha256)
    assertThat(with(Keys.sequential()).pseudonymise("alice@example.com").value())
        .isEqualTo("a59fc578d4cb46faab1d6eb348e7c74b33b85122d6459fdb7bf5654b333acab4");
  }

  @Test
  void cpAc09_aPseudonymDoesNotContainTheIdentity() {
    Pseudonym pseudonym = with(Keys.sequential()).pseudonymise("alice@example.com");

    assertThat(pseudonym.value()).doesNotContain("alice").doesNotContain("example");
    assertThat(pseudonym.toString()).doesNotContain("alice");
  }

  @Test
  void theSameIdentityWithADifferentKeyIsADifferentPseudonym() {
    assertThat(with(Keys.filled(1)).pseudonymise("alice@example.com"))
        .isNotEqualTo(with(Keys.filled(2)).pseudonymise("alice@example.com"));
  }

  @Test
  void differentIdentitiesAreDifferentPseudonyms() {
    IdentityPseudonymiser pseudonymiser = with(Keys.sequential());

    assertThat(pseudonymiser.pseudonymise("alice@example.com"))
        .isNotEqualTo(pseudonymiser.pseudonymise("bob@example.com"));
  }

  @Test
  void theDirectoryRemembersWhichIdentityAPseudonymStandsFor() {
    Pseudonym pseudonym = with(Keys.sequential()).pseudonymise("Alice@Example.com ");

    assertThat(directory.lookup(pseudonym)).contains("alice@example.com");
  }

  @Test
  void aBlankIdentityCannotBePseudonymised() {
    IdentityPseudonymiser pseudonymiser = with(Keys.sequential());

    assertThatThrownBy(() -> pseudonymiser.pseudonymise("   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theKeyIsLoadedOnceAndHeld() {
    AtomicInteger loads = new AtomicInteger();
    PseudonymKey key = new PseudonymKey(Keys.sequential());
    IdentityPseudonymiser pseudonymiser =
        new IdentityPseudonymiser(
            () -> {
              loads.incrementAndGet();
              return key;
            },
            directory);

    pseudonymiser.pseudonymise("a");
    pseudonymiser.pseudonymise("b");
    pseudonymiser.pseudonymise("c");

    assertThat(loads).hasValue(1);
  }

  @Test
  void anUnavailableKeyStoreFailsClosedAndIsNotCachedSoTheNextCallRetries() {
    AtomicInteger calls = new AtomicInteger();
    PseudonymKey key = new PseudonymKey(Keys.sequential());
    IdentityPseudonymiser pseudonymiser =
        new IdentityPseudonymiser(
            () -> {
              if (calls.incrementAndGet() == 1) {
                throw new KeyStoreUnavailableException("vault down");
              }
              return key;
            },
            directory);

    assertThatThrownBy(() -> pseudonymiser.pseudonymise("alice"))
        .isInstanceOf(KeyStoreUnavailableException.class);
    assertThat(pseudonymiser.pseudonymise("alice")).isNotNull();
  }

  @Test
  void aKeyShorterThanTheHashIsRejected() {
    assertThatThrownBy(() -> new PseudonymKey(new byte[31]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aKeyNeverShowsItselfInItsStringForm() {
    assertThat(new PseudonymKey(Keys.sequential()).toString()).isEqualTo("PseudonymKey[redacted]");
  }

  @Test
  void aPseudonymMustLookLikeOneSoARawIdentityCannotBeSmuggledIn() {
    assertThatThrownBy(() -> Pseudonym.fromStored("alice@example.com"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
