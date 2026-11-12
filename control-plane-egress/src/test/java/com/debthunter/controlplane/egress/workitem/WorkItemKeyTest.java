package com.debthunter.controlplane.egress.workitem;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkItemKeyTest {

  @Test
  void aKeyIsTheSameEveryTimeAndLabelSafe() {
    String key = WorkItemKey.of("acme/widgets", "root", "fp");

    assertThat(key).isEqualTo(WorkItemKey.of("acme/widgets", "root", "fp"));
    assertThat(key).matches("dh-[0-9a-f]{32}");
  }

  @Test
  void aDifferentFindingHasADifferentKey() {
    String key = WorkItemKey.of("acme/widgets", "root", "fp");

    assertThat(key)
        .isNotEqualTo(WorkItemKey.of("acme/widgets", "root", "fp2"))
        .isNotEqualTo(WorkItemKey.of("acme/widgets", "other", "fp"))
        .isNotEqualTo(WorkItemKey.of("acme/gadgets", "root", "fp"));
  }

  @Test
  void partsCannotBeShuffledAcrossTheBoundaryToCollide() {
    assertThat(WorkItemKey.of("ab", "c", "d")).isNotEqualTo(WorkItemKey.of("a", "bc", "d"));
  }

  @Test
  void aKeyRevealsNothingAboutTheFinding() {
    assertThat(WorkItemKey.of("secret-repo", "root", "secret-fp")).doesNotContain("secret");
  }
}
