package com.debthunter.controlplane.store.findings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PseudonymiserPersistenceTest {

  private static final Pseudonym P1 = Pseudonym.fromStored("1".repeat(64));
  private static final Pseudonym P2 = Pseudonym.fromStored("2".repeat(64));

  private static PseudonymisedFinding finding(String id, Pseudonym... contributors) {
    return new PseudonymisedFinding(
        id,
        "rule",
        Category.HOTSPOT,
        Severity.HIGH,
        0.9,
        "src/A.java",
        0,
        "message",
        Map.of("changeFrequency", 1.0),
        1.0,
        true,
        "fp-" + id,
        List.of(contributors));
  }

  private final InMemoryPseudonymisedFindingStore store = new InMemoryPseudonymisedFindingStore();

  @Test
  void aStoredRunReadsBackWithItsPseudonymsIntact() {
    store.saveRun("run-1", "acme/widgets", List.of(finding("f1", P1, P2)));

    assertThat(store.findingsForRun("run-1"))
        .singleElement()
        .satisfies(f -> assertThat(f.contributors()).containsExactly(P1, P2));
  }

  @Test
  void savingARunTwiceLeavesOneCopy() {
    store.saveRun("run-1", "acme/widgets", List.of(finding("f1", P1)));
    store.saveRun("run-1", "acme/widgets", List.of(finding("f1", P1)));

    assertThat(store.findingsForRun("run-1")).hasSize(1);
  }

  @Test
  void anUnknownRunHasNoFindings() {
    assertThat(store.findingsForRun("nope")).isEmpty();
  }

  @Test
  void aStoredFindingCannotBeMutatedThroughTheListItWasBuiltFrom() {
    var contributors = new java.util.ArrayList<>(List.of(P1));
    PseudonymisedFinding f = finding("f1");
    PseudonymisedFinding withContributors =
        new PseudonymisedFinding(
            f.id(),
            f.ruleId(),
            f.category(),
            f.severity(),
            f.confidence(),
            f.path(),
            f.startLine(),
            f.message(),
            f.evidence(),
            f.score(),
            f.isNew(),
            f.fingerprint(),
            contributors);

    contributors.add(P2);

    assertThat(withContributors.contributors()).containsExactly(P1);
  }

  @Test
  void aRawIdentityIsRejectedAtTheTypeBoundaryBecauseItIsNotAPseudonym() {
    assertThatThrownBy(() -> Pseudonym.fromStored("alice@example.com"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Pseudonym.fromStored("A".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Pseudonym.fromStored("1".repeat(63)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
