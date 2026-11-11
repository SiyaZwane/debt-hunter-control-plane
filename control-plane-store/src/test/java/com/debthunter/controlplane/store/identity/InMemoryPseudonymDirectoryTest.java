package com.debthunter.controlplane.store.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InMemoryPseudonymDirectoryTest {

  private final InMemoryPseudonymDirectory directory = new InMemoryPseudonymDirectory();
  private final Pseudonym pseudonym = Pseudonym.fromStored("a".repeat(64));

  @Test
  void aRememberedPseudonymCanBeLookedUp() {
    directory.remember(pseudonym, "alice");

    assertThat(directory.lookup(pseudonym)).contains("alice");
  }

  @Test
  void rememberingTwiceIsHarmless() {
    directory.remember(pseudonym, "alice");
    directory.remember(pseudonym, "alice");

    assertThat(directory.lookup(pseudonym)).contains("alice");
  }

  @Test
  void anUnknownPseudonymIsEmpty() {
    assertThat(directory.lookup(pseudonym)).isEmpty();
  }

  @Test
  void pseudonymsAreEqualByValue() {
    assertThat(Pseudonym.fromStored("a".repeat(64)))
        .isEqualTo(pseudonym)
        .hasSameHashCodeAs(pseudonym);
  }
}
