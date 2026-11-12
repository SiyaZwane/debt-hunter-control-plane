package com.debthunter.controlplane.egress.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DigestTextTest {

  private static DigestMessage message(String msg, String path, String reason) {
    return new DigestMessage(
        "team-a",
        "2026-07-01T00:00:00Z",
        "2026-07-02T00:00:00Z",
        Map.of("HIGH", 2, "LOW", 1),
        3,
        1,
        List.of(new DigestMessage.Expiring("acme/widgets", "2026-07-05T00:00:00Z", reason)),
        Map.of("HIGH", 4),
        List.of(new DigestMessage.StageLine("acme/widgets", "STAGE_3")),
        2,
        List.of(new DigestMessage.Notable("HIGH", "codemaat.hotspot", path, msg, true)),
        4,
        6,
        "https://cp.example/squads/team-a");
  }

  @Test
  void rendersEverySectionForSlack() {
    String text =
        DigestText.render(message("a hotspot", "src/A.java", "later"), DigestText.Style.SLACK);

    assertThat(text)
        .contains(
            "*Debt Hunter digest for `team-a`*",
            "New findings: 3 (HIGH 2, LOW 1)",
            "Resolved: 3, regressed: 1",
            "Open now: 4 (HIGH 4)",
            "*Suppressions ending soon*",
            "ends 2026-07-05T00:00:00Z: `later`",
            "Files with a new or returning finding: 6",
            "• HIGH `codemaat.hotspot` in `src/A.java`: `a hotspot` (came back)",
            "…and 4 more",
            "• `acme/widgets` STAGE_3",
            "…and 2 more repositories",
            "Details: https://cp.example/squads/team-a",
            "re-run the Debt Hunter scan");
    assertThat(text).doesNotContain("\n\n");
  }

  @Test
  void teamsUsesDoubleBoldAndBlankLines() {
    String text = DigestText.render(message("m", "p", "r"), DigestText.Style.TEAMS);

    assertThat(text).contains("**Debt Hunter digest for `team-a`**", "\n\n");
  }

  @Test
  void aQuietSquadReadsAsNone() {
    DigestMessage quiet =
        new DigestMessage(
            "t", "a", "b", Map.of(), 0, 0, List.of(), Map.of(), List.of(), 0, List.of(), 0, 0,
            null);

    String text = DigestText.render(quiet, DigestText.Style.SLACK);

    assertThat(text).contains("New findings: none", "Open now: none").doesNotContain("Details:");
    assertThat(text).doesNotContain("Worth a look").doesNotContain("Rollout stages");
  }

  @Test
  void markupAndMentionsFromARepositoryCannotActInTheChannel() {
    String text =
        DigestText.render(
            message("<!channel> <@U123> <https://evil|click> & `x`", "a/<b>.java", "ping <!here>"),
            DigestText.Style.SLACK);

    assertThat(text).doesNotContain("<!channel>", "<@U123>", "<https://evil", "<!here>", "<b>");
    assertThat(text).contains("&lt;!channel&gt;", "&amp;");
    assertThat(text).contains("'x'").doesNotContain("`x`");
  }

  @Test
  void cleaningStripsControlCharactersBreaksMentionsAndCuts() {
    assertThat(DigestText.clean("a\nb\u0007c @bob", 100)).doesNotContain("\n", "\u0007", "@bob");
    assertThat(DigestText.clean("x".repeat(500), 200)).hasSize(201).endsWith("…");
    assertThat(DigestText.clean("short", 200)).isEqualTo("short");
  }
}
