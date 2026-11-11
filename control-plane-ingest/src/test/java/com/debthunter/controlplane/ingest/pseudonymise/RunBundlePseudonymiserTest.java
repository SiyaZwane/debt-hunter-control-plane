package com.debthunter.controlplane.ingest.pseudonymise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.ingest.normalise.NormalisedRunBundle;
import com.debthunter.controlplane.ingest.normalise.RunNormaliser;
import com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory;
import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.controlplane.store.identity.PseudonymDirectory;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

class RunBundlePseudonymiserTest {

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .addModule(new SimpleModule().addSerializer(Pseudonym.class, ToStringSerializer.instance))
          .build();

  private final InMemoryPseudonymDirectory directory = new InMemoryPseudonymDirectory();
  private final RunBundlePseudonymiser pseudonymiser =
      new RunBundlePseudonymiser(
          new IdentityPseudonymiser(new InMemoryPseudonymKeyStore(Keys.sequential()), directory));

  private static NormalisedRunBundle normalised(
      java.util.function.Consumer<ObjectNode> editEvidence) throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(RunBundleFixtures.validV1());
    editEvidence.accept((ObjectNode) root.at("/findings/0/evidence"));
    return new RunNormaliser().normalise(IntakeEnvelopes.forBundle(JSON.writeValueAsString(root)));
  }

  @Test
  void authorsBecomePseudonymsAndNoRawIdentityRemainsAnywhereInTheOutput() throws Exception {
    NormalisedRunBundle bundle =
        normalised(e -> e.putArray("authors").add("alice@example.com").add("Bob Builder"));

    PseudonymisedRunBundle result = pseudonymiser.pseudonymise(bundle);

    String everything = JSON.writeValueAsString(result);
    assertThat(everything)
        .doesNotContainIgnoringCase("alice")
        .doesNotContainIgnoringCase("builder");
    assertThat(result.findings().get(0).contributors()).hasSize(2);
    assertThat(result.findings().get(0).evidence()).doesNotContainKey("authors");
  }

  @Test
  void evidenceKeysKnownToBeSafeAreKept() throws Exception {
    PseudonymisedRunBundle result = pseudonymiser.pseudonymise(normalised(e -> {}));

    assertThat(result.findings().get(0).evidence()).containsEntry("changeFrequency", 12.0);
  }

  @Test
  void evidenceUnderAnUnreviewedKeyIsDroppedAndCountedBecauseItMightNameSomeone() throws Exception {
    NormalisedRunBundle bundle =
        normalised(e -> e.put("lastEditor", "alice@example.com").put("owner", "bob"));

    PseudonymisedRunBundle result = pseudonymiser.pseudonymise(bundle);

    assertThat(result.findings().get(0).evidence()).doesNotContainKeys("lastEditor", "owner");
    assertThat(JSON.writeValueAsString(result)).doesNotContain("alice");
    assertThat(result.pseudonymisation().evidenceEntriesDropped()).isEqualTo(2);
  }

  @Test
  void anAuthorsEntryOfUnexpectedShapeIsDroppedNotKept() throws Exception {
    NormalisedRunBundle bundle = normalised(e -> e.put("authors", "alice@example.com"));

    PseudonymisedRunBundle result = pseudonymiser.pseudonymise(bundle);

    assertThat(JSON.writeValueAsString(result)).doesNotContain("alice");
    assertThat(result.pseudonymisation().evidenceEntriesDropped()).isEqualTo(1);
  }

  @Test
  void everythingThatIsNotIdentityIsCarriedAcrossUnchanged() throws Exception {
    NormalisedRunBundle bundle = normalised(e -> e.putArray("authors").add("alice"));

    PseudonymisedRunBundle result = pseudonymiser.pseudonymise(bundle);

    assertThat(result.run()).isEqualTo(bundle.scan().run());
    assertThat(result.policy()).isEqualTo(bundle.scan().policy());
    assertThat(result.provenance()).isEqualTo(bundle.provenance());
    var original = bundle.scan().findings().get(0);
    var pseudonymised = result.findings().get(0);
    assertThat(pseudonymised.id()).isEqualTo(original.id());
    assertThat(pseudonymised.fingerprint()).isEqualTo(original.fingerprint());
    assertThat(pseudonymised.severity()).isEqualTo(original.severity());
    assertThat(pseudonymised.score()).isEqualTo(original.score());
  }

  @Test
  void anUnavailableKeyFailsTheWholeBundleEvenWhenItHasNoContributors() {
    RunBundlePseudonymiser unavailable =
        new RunBundlePseudonymiser(
            new IdentityPseudonymiser(
                () -> {
                  throw new KeyStoreUnavailableException("vault down");
                },
                directory));
    NormalisedRunBundle noAuthors =
        new RunNormaliser().normalise(IntakeEnvelopes.forBundle(RunBundleFixtures.validV1()));

    assertThatThrownBy(() -> unavailable.pseudonymise(noAuthors))
        .isInstanceOf(KeyStoreUnavailableException.class);
  }

  @Test
  void checkingForTheKeyLeavesNothingInTheDirectory() {
    java.util.List<String> remembered = new java.util.ArrayList<>();
    PseudonymDirectory recording =
        new PseudonymDirectory() {
          @Override
          public void remember(Pseudonym pseudonym, String identity) {
            remembered.add(identity);
          }

          @Override
          public java.util.Optional<String> lookup(Pseudonym pseudonym) {
            return java.util.Optional.empty();
          }
        };
    RunBundlePseudonymiser noAuthors =
        new RunBundlePseudonymiser(
            new IdentityPseudonymiser(new InMemoryPseudonymKeyStore(Keys.sequential()), recording));

    noAuthors.pseudonymise(
        new RunNormaliser().normalise(IntakeEnvelopes.forBundle(RunBundleFixtures.validV1())));

    assertThat(remembered).isEmpty();
  }
}
