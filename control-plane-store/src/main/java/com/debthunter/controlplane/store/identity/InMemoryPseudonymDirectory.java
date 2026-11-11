package com.debthunter.controlplane.store.identity;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link PseudonymDirectory} held in memory, for tests and local development. */
public final class InMemoryPseudonymDirectory implements PseudonymDirectory {

  private final Map<Pseudonym, String> identities = new ConcurrentHashMap<>();

  @Override
  public void remember(Pseudonym pseudonym, String identity) {
    identities.put(pseudonym, identity);
  }

  @Override
  public Optional<String> lookup(Pseudonym pseudonym) {
    return Optional.ofNullable(identities.get(pseudonym));
  }
}
