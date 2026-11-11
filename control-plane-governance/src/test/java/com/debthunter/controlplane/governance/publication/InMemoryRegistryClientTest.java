package com.debthunter.controlplane.governance.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class InMemoryRegistryClientTest {

  private static final Signature SIG = new Signature("k", "alg", "v");
  private final InMemoryRegistryClient registry = new InMemoryRegistryClient();

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void contentIsAddressedByItsOwnDigest() {
    String digest = registry.push("r", "1.0", bytes("a"), SIG);

    assertThat(digest).isEqualTo(BundleDigest.of(bytes("a")));
    assertThat(registry.pull("r", digest)).isPresent();
    assertThat(registry.pull("r", "1.0")).isPresent();
  }

  @Test
  void pushingTheSameContentUnderTheSameTagAgainIsIdempotent() {
    String first = registry.push("r", "1.0", bytes("a"), SIG);

    assertThat(registry.push("r", "1.0", bytes("a"), SIG)).isEqualTo(first);
  }

  @Test
  void aTagCannotBeMovedToDifferentContent() {
    registry.push("r", "1.0", bytes("a"), SIG);

    assertThatThrownBy(() -> registry.push("r", "1.0", bytes("b"), SIG))
        .isInstanceOf(TagConflictException.class);
    assertThat(registry.pull("r", "1.0").orElseThrow().content()).isEqualTo(bytes("a"));
  }

  @Test
  void anUnknownReferenceIsEmptyNotAnError() {
    assertThat(registry.pull("r", "9.9")).isEmpty();
    assertThat(registry.pull("r", "sha256:" + "0".repeat(64))).isEmpty();
  }

  @Test
  void repositoriesAreIsolated() {
    registry.push("one", "1.0", bytes("a"), SIG);

    assertThat(registry.pull("two", "1.0")).isEmpty();
  }

  @Test
  void theClientHasNoWayToDeleteOrOverwriteAnArtefact() {
    for (Method method : OrasRegistryClient.class.getMethods()) {
      assertThat(method.getName())
          .doesNotContainIgnoringCase("delete")
          .doesNotContainIgnoringCase("remove")
          .doesNotContainIgnoringCase("prune");
    }
    assertThat(Arrays.stream(OrasRegistryClient.class.getMethods()).map(Method::getName))
        .containsExactlyInAnyOrder("push", "pull");
  }

  @Test
  void storedContentIsNotAffectedByLaterChangesToTheArrayThatWasPushed() {
    byte[] content = bytes("abc");
    registry.push("r", "1.0", content, SIG);
    content[0] = 'z';

    assertThat(registry.pull("r", "1.0").orElseThrow().content()).isEqualTo(bytes("abc"));
  }
}
