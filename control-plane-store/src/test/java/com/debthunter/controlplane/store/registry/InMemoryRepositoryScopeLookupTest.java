package com.debthunter.controlplane.store.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryRepositoryScopeLookupTest {

  private final RepositoryScopeLookup lookup =
      new InMemoryRepositoryScopeLookup(
          Map.of("svc-a", List.of("acme/widgets", "acme/gadgets"), "svc-b", List.of("acme/other")));

  @Test
  void anIdentityMayWriteForARepositoryItIsScopedTo() {
    assertThat(lookup.mayWrite("svc-a", "acme/widgets")).isTrue();
    assertThat(lookup.mayWrite("svc-a", "acme/gadgets")).isTrue();
  }

  @Test
  void anIdentityMayNotWriteForARepositoryOutsideItsScope() {
    assertThat(lookup.mayWrite("svc-a", "acme/other")).isFalse();
  }

  @Test
  void anUnknownIdentityMayWriteForNothing() {
    assertThat(lookup.mayWrite("svc-unknown", "acme/widgets")).isFalse();
  }

  @Test
  void aNullIdentityOrRepositoryFailsClosed() {
    assertThat(lookup.mayWrite(null, "acme/widgets")).isFalse();
    assertThat(lookup.mayWrite("svc-a", null)).isFalse();
  }

  @Test
  void laterChangesToTheSourceMapDoNotWidenTheScope() {
    List<String> repositories = new ArrayList<>(List.of("acme/widgets"));
    Map<String, List<String>> source = new HashMap<>(Map.of("svc-a", repositories));
    RepositoryScopeLookup snapshot = new InMemoryRepositoryScopeLookup(source);

    repositories.add("acme/secret");
    source.put("svc-evil", List.of("acme/widgets"));

    assertThat(snapshot.mayWrite("svc-a", "acme/secret")).isFalse();
    assertThat(snapshot.mayWrite("svc-evil", "acme/widgets")).isFalse();
  }
}
