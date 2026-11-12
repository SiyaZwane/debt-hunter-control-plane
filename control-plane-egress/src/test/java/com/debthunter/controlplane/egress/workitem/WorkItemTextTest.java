package com.debthunter.controlplane.egress.workitem;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.suppressions.Suppression;
import com.debthunter.domain.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkItemTextTest {

  private static final PseudonymisedFinding FINDING =
      WorkItemHarness.finding("fp-1", Severity.HIGH);

  @Test
  void contentNamesTheFindingWithoutAnyContributor() {
    WorkItemContent c = WorkItemText.content("dh-1", FINDING, Optional.of("https://x/runs/1"));

    assertThat(c.key()).isEqualTo("dh-1");
    assertThat(c.title()).isEqualTo("Technical debt: codemaat.hotspot in src/Foo.java");
    assertThat(c.runLink()).isEqualTo("https://x/runs/1");
    assertThat(c.details())
        .extracting(WorkItemContent.Detail::label)
        .containsExactly("Rule", "Severity", "Category", "Where", "What was found");
    assertThat(c.details().toString()).doesNotContain(WorkItemHarness.CONTRIBUTOR);
  }

  @Test
  void noRunLinkMeansNone() {
    assertThat(WorkItemText.content("k", FINDING, Optional.empty()).runLink()).isNull();
  }

  @Test
  void aTitleIsOneShortLine() {
    PseudonymisedFinding long1 =
        new PseudonymisedFinding(
            "f",
            "rule",
            FINDING.category(),
            Severity.HIGH,
            0.5,
            "a/".repeat(200) + "\nFoo.java",
            1,
            "m",
            java.util.Map.of(),
            0,
            true,
            "fp",
            java.util.List.of());

    String title = WorkItemText.content("k", long1, Optional.empty()).title();

    assertThat(title).doesNotContain("\n").hasSizeLessThanOrEqualTo(121).endsWith("…");
  }

  @Test
  void mentionsAreNeutralisedAndControlCharactersRemoved() {
    assertThat(WorkItemText.plain("hello @bob\u0007 there\nnow", 100))
        .doesNotContain("@bob")
        .doesNotContain("\u0007")
        .doesNotContain("\n")
        .contains("bob");
    assertThat(WorkItemText.plain("a @ b and email@x.com", 100)).contains("a @ b");
  }

  @Test
  void textIsCutToTheLimitAndSaysSo() {
    assertThat(WorkItemText.plain("x".repeat(500), 300)).hasSize(301).endsWith("…");
    assertThat(WorkItemText.plain("short", 300)).isEqualTo("short");
  }

  @Test
  void commentsSayWhatHappenedAndWhy() {
    assertThat(WorkItemText.closed()).contains("no longer present");
    assertThat(WorkItemText.reopenedRegression()).contains("reappeared");
    assertThat(WorkItemText.reopenedSuppressionEnded()).contains("suppression has expired");
    assertThat(WorkItemText.reopened()).contains("open");
  }

  @Test
  void aHoldCommentCarriesTheReasonAndTheEndDate() {
    Instant start = Instant.parse("2026-07-01T00:00:00Z");
    Suppression s =
        new Suppression(
            "s", "r", "fp", "waiting on vendor", start, start.plus(Duration.ofDays(30)), 90, null);

    String comment = WorkItemText.onHold(Optional.of(s));

    assertThat(comment).contains("waiting on vendor", "2026-07-31T00:00:00Z", "reopened");
    assertThat(WorkItemText.onHold(Optional.empty())).contains("suppressed");
  }
}
