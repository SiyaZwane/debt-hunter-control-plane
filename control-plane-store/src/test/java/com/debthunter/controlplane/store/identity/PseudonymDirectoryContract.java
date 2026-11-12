package com.debthunter.controlplane.store.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

abstract class PseudonymDirectoryContract {

  static final Pseudonym A = Pseudonym.fromStored("a".repeat(64));
  static final Pseudonym B = Pseudonym.fromStored("b".repeat(64));

  protected abstract PseudonymDirectory newDirectory();

  @Test
  void aRememberedIdentityIsFound() {
    PseudonymDirectory d = newDirectory();
    d.remember(A, "alice@example.com");

    assertThat(d.lookup(A)).contains("alice@example.com");
  }

  @Test
  void anUnknownPseudonymIsEmpty() {
    PseudonymDirectory d = newDirectory();
    d.remember(A, "alice@example.com");

    assertThat(d.lookup(B)).isEmpty();
  }

  @Test
  void rememberingTheSamePseudonymAgainIsHarmlessAndTheFirstIdentityIsKept() {
    PseudonymDirectory d = newDirectory();
    d.remember(A, "alice@example.com");
    d.remember(A, "alice@example.com");
    d.remember(A, "someone-else@example.com");

    assertThat(d.lookup(A)).contains("alice@example.com");
  }

  @Test
  void pseudonymsAreKeptApart() {
    PseudonymDirectory d = newDirectory();
    d.remember(A, "alice@example.com");
    d.remember(B, "bob@example.com");

    assertThat(d.lookup(A)).contains("alice@example.com");
    assertThat(d.lookup(B)).contains("bob@example.com");
  }

  @Test
  void identitiesWithUnusualCharactersSurvive() {
    PseudonymDirectory d = newDirectory();
    d.remember(A, "Zoë O’Brien <zoe+test@exämple.com>");

    assertThat(d.lookup(A)).contains("Zoë O’Brien <zoe+test@exämple.com>");
  }
}
