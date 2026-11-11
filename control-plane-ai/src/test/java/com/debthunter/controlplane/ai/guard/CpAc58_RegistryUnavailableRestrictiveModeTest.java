package com.debthunter.controlplane.ai.guard;

import static com.debthunter.controlplane.ai.AiFixtures.hotspot;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * CP-AC-58: when the registry cannot say whether a repository opted in, the guard assumes it did
 * not. The explanation still goes ahead, without source.
 */
class CpAc58_RegistryUnavailableRestrictiveModeTest {

  private final GuardFixture f = new GuardFixture().optedIn("acme/a");

  @Test
  void cpAc58_aRepositoryThatDidOptInIsTreatedAsRestrictedWhileTheRegistryIsDown() {
    f.lookupDown = true;

    f.guard.dispatch("acme/a", hotspot(), f.model);

    assertThat(f.model.lastPromptText()).doesNotContain("SOURCE-MARKER");
  }

  @Test
  void cpAc58_theExplanationIsStillProducedAndTheRestrictionIsRecorded() {
    f.lookupDown = true;

    ModelResult result = f.guard.dispatch("acme/a", hotspot(), f.model);

    assertThat(result.text()).isNotBlank();
    assertThat(f.log.records())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.restrictedByUnknownChoice()).isTrue();
              assertThat(r.sourceIncluded()).isFalse();
            });
  }

  @Test
  void cpAc58_oncetheRegistryIsBackTheOptInAppliesAgain() {
    f.lookupDown = true;
    f.guard.dispatch("acme/a", hotspot(), f.model);
    f.lookupDown = false;

    f.guard.dispatch("acme/a", hotspot(), f.model);

    assertThat(f.model.lastPromptText()).contains("SOURCE-MARKER");
  }
}
