package com.debthunter.controlplane.ingest.pseudonymise;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.ingest.normalise.RunNormaliser;
import com.debthunter.controlplane.store.evidence.EvidenceStorageService;
import com.debthunter.controlplane.store.evidence.InMemoryEvidenceRepository;
import com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EvidenceSinkTest {

  private final EvidenceStorageService service =
      new EvidenceStorageService(new InMemoryEvidenceRepository(), Duration.ofDays(30));

  @Test
  void evidenceOfEachFindingIsStoredAndHoldsNoRawIdentity() {
    var pseudonymiser =
        new RunBundlePseudonymiser(
            new IdentityPseudonymiser(
                new InMemoryPseudonymKeyStore(new byte[32]), new InMemoryPseudonymDirectory()));
    PseudonymisedRunBundle bundle =
        pseudonymiser.pseudonymise(
            new RunNormaliser()
                .normalise(
                    IntakeEnvelopes.forBundle(
                        RunBundleBuilder.run()
                            .id("run-1")
                            .observing("fp-a", "fp-b")
                            .authoredBy("alice@example.com")
                            .build())));

    new EvidenceSink(service).accept(bundle);

    assertThat(service.hotFor("run-1")).hasSize(2);
    assertThat(service.hotFor("run-1").toString()).doesNotContainIgnoringCase("alice");
  }
}
