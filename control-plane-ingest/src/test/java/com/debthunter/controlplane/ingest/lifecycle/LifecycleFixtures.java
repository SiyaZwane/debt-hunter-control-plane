package com.debthunter.controlplane.ingest.lifecycle;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.ingest.normalise.RunNormaliser;
import com.debthunter.controlplane.ingest.pseudonymise.IdentityPseudonymiser;
import com.debthunter.controlplane.ingest.pseudonymise.InMemoryPseudonymKeyStore;
import com.debthunter.controlplane.ingest.pseudonymise.PseudonymisedRunBundle;
import com.debthunter.controlplane.ingest.pseudonymise.RunBundlePseudonymiser;
import com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import java.time.Instant;

/** Shared builders for lifecycle tests. */
final class LifecycleFixtures {

  static final String REPO = "acme/widgets";

  private static final RunBundlePseudonymiser PSEUDONYMISER =
      new RunBundlePseudonymiser(
          new IdentityPseudonymiser(
              new InMemoryPseudonymKeyStore(new byte[32]), new InMemoryPseudonymDirectory()));

  private LifecycleFixtures() {}

  static PseudonymisedRunBundle bundle(RunBundleBuilder builder) {
    return PSEUDONYMISER.pseudonymise(
        new RunNormaliser().normalise(IntakeEnvelopes.forBundle(builder.build())));
  }

  static Instant day(int n) {
    return Instant.parse("2026-01-01T00:00:00Z").plusSeconds(86_400L * n);
  }
}
