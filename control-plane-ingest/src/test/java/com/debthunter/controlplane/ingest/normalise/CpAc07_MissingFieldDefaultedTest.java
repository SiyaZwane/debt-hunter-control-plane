package com.debthunter.controlplane.ingest.normalise;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import com.debthunter.domain.Finding;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-07: a field the current schema has and an older bundle lacks is set to its documented
 * default, and the fact that it was defaulted is recorded with the run.
 */
class CpAc07_MissingFieldDefaultedTest {

  private final NormalisedRunBundle bundle =
      new RunNormaliser().normalise(IntakeEnvelopes.forBundle(RunBundleFixtures.validV0()));

  @Test
  void cpAc07_aMissingFindingFieldTakesItsDocumentedDefault() {
    Finding finding = bundle.scan().findings().get(0);

    assertThat(finding.confidence()).isEqualTo(NormalisationDefaults.CONFIDENCE);
    assertThat(finding.score()).isEqualTo(NormalisationDefaults.SCORE);
    assertThat(finding.isNew()).isEqualTo(NormalisationDefaults.IS_NEW);
    assertThat(finding.evidence()).isEqualTo(NormalisationDefaults.EVIDENCE);
  }

  @Test
  void cpAc07_aMissingRunOrPolicyFieldTakesItsDocumentedDefault() {
    assertThat(bundle.scan().metrics()).isEmpty();
    assertThat(bundle.scan().policy().bundleVersion())
        .isEqualTo(NormalisationDefaults.POLICY_BUNDLE_VERSION);
    assertThat(bundle.scan().run().degraded()).isFalse();
  }

  @Test
  void cpAc07_everyDefaultingIsRecordedInTheRunMetadata() {
    assertThat(bundle.report().wasDefaulted("findings[].confidence")).isTrue();
    assertThat(bundle.report().wasDefaulted("findings[].score")).isTrue();
    assertThat(bundle.report().wasDefaulted("findings[].isNew")).isTrue();
    assertThat(bundle.report().wasDefaulted("findings[].evidence")).isTrue();
    assertThat(bundle.report().wasDefaulted("metrics")).isTrue();
    assertThat(bundle.report().wasDefaulted("policy.bundleVersion")).isTrue();
    assertThat(bundle.report().wasDefaulted("run.degraded")).isTrue();
  }

  @Test
  void cpAc07_aFieldTheBundleDidCarryIsNotRecordedAsDefaulted() {
    assertThat(bundle.report().wasDefaulted("run.id")).isFalse();
    assertThat(bundle.report().wasDefaulted("findings[].fingerprint")).isFalse();
    assertThat(bundle.report().wasDefaulted("policy.status")).isFalse();
  }
}
