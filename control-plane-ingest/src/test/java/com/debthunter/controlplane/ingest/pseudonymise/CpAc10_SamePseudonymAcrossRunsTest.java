package com.debthunter.controlplane.ingest.pseudonymise;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.ingest.normalise.RunNormaliser;
import com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory;
import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CP-AC-10: the same contributor is the same pseudonym in every run, so trends can follow them. */
class CpAc10_SamePseudonymAcrossRunsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static String bundleWithAuthors(String runId, String... authors) throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(RunBundleFixtures.validV1(runId, "acme/widgets"));
    ArrayNode list = ((ObjectNode) root.at("/findings/0/evidence")).putArray("authors");
    for (String author : authors) {
      list.add(author);
    }
    return JSON.writeValueAsString(root);
  }

  private PseudonymisedRunBundle ingest(RunBundlePseudonymiser pseudonymiser, String bundle) {
    return pseudonymiser.pseudonymise(
        new RunNormaliser().normalise(IntakeEnvelopes.forBundle(bundle)));
  }

  private static RunBundlePseudonymiser pseudonymiserWith(byte[] key) {
    return new RunBundlePseudonymiser(
        new IdentityPseudonymiser(
            new InMemoryPseudonymKeyStore(key), new InMemoryPseudonymDirectory()));
  }

  @Test
  void cpAc10_theSameContributorIsTheSamePseudonymInTwoRuns() throws Exception {
    RunBundlePseudonymiser pseudonymiser = pseudonymiserWith(Keys.sequential());

    List<Pseudonym> first =
        ingest(pseudonymiser, bundleWithAuthors("run-1", "alice@example.com", "bob@example.com"))
            .findings()
            .get(0)
            .contributors();
    List<Pseudonym> second =
        ingest(pseudonymiser, bundleWithAuthors("run-2", "bob@example.com", "alice@example.com"))
            .findings()
            .get(0)
            .contributors();

    assertThat(second).containsExactlyInAnyOrderElementsOf(first);
  }

  @Test
  void cpAc10_itSurvivesARestartBecauseTheKeyNotTheProcessDecidesIt() throws Exception {
    String bundle = bundleWithAuthors("run-1", "alice@example.com");

    Pseudonym beforeRestart =
        ingest(pseudonymiserWith(Keys.sequential()), bundle)
            .findings()
            .get(0)
            .contributors()
            .get(0);
    Pseudonym afterRestart =
        ingest(pseudonymiserWith(Keys.sequential()), bundle)
            .findings()
            .get(0)
            .contributors()
            .get(0);

    assertThat(afterRestart).isEqualTo(beforeRestart);
  }

  @Test
  void cpAc10_aDifferentDeploymentKeyGivesDifferentPseudonymsSoDeploymentsCannotBeLinked()
      throws Exception {
    String bundle = bundleWithAuthors("run-1", "alice@example.com");

    Pseudonym a =
        ingest(pseudonymiserWith(Keys.filled(1)), bundle).findings().get(0).contributors().get(0);
    Pseudonym b =
        ingest(pseudonymiserWith(Keys.filled(2)), bundle).findings().get(0).contributors().get(0);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void cpBc05_cosmeticDifferencesInHowAnIdentityIsWrittenDoNotSplitOnePersonInTwo()
      throws Exception {
    RunBundlePseudonymiser pseudonymiser = pseudonymiserWith(Keys.sequential());

    List<Pseudonym> variants =
        ingest(
                pseudonymiser,
                bundleWithAuthors(
                    "run-1", "alice@example.com", " Alice@Example.COM ", "ALICE@EXAMPLE.COM"))
            .findings()
            .get(0)
            .contributors();

    assertThat(variants).hasSize(1);
  }

  @Test
  void cpBc07_blankOrAbsentIdentitiesAreDroppedAndCountedNotPseudonymisedIntoOneSharedBucket()
      throws Exception {
    RunBundlePseudonymiser pseudonymiser = pseudonymiserWith(Keys.sequential());
    ObjectNode root =
        (ObjectNode) JSON.readTree(bundleWithAuthors("run-1", "alice@example.com", "", "   "));
    ((ArrayNode) root.at("/findings/0/evidence/authors")).addNull();

    PseudonymisedRunBundle bundle = ingest(pseudonymiser, JSON.writeValueAsString(root));

    assertThat(bundle.findings().get(0).contributors()).hasSize(1);
    assertThat(bundle.pseudonymisation().blankIdentitiesDropped()).isEqualTo(2);
  }

  @Test
  void aFindingWithNoContributorsHasNone() throws Exception {
    PseudonymisedRunBundle bundle =
        ingest(pseudonymiserWith(Keys.sequential()), RunBundleFixtures.validV1());

    assertThat(bundle.findings().get(0).contributors()).isEmpty();
    assertThat(JSON.valueToTree(bundle.pseudonymisation()).get("blankIdentitiesDropped").asInt())
        .isZero();
    assertThat((JsonNode) JSON.valueToTree(bundle.pseudonymisation())).isNotNull();
  }
}
