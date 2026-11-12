package com.debthunter.controlplane.egress.digest;

import static com.debthunter.controlplane.egress.digest.DigestHarness.CONTRIBUTOR;
import static com.debthunter.controlplane.egress.digest.DigestHarness.TEAM_A;
import static com.debthunter.controlplane.egress.digest.DigestHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.domain.Severity;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** CP-AC-74: no contributor identity in any notification. */
class CpAc74_NoIdentityInNotificationTest {

  @Test
  void theDigestAndEveryRenderingOfItCarryNoContributor() {
    DigestHarness h = InMemoryDigestParts.harness();
    h.slackFor(TEAM_A);
    h.start();
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "hotspot");
    h.nextPeriod();
    h.pump();
    DigestMessage digest = h.slack.posted.get(0).message();

    String json = DigestNotificationService.write(digest);
    String slack = DigestText.render(digest, DigestText.Style.SLACK);
    String teams = DigestText.render(digest, DigestText.Style.TEAMS);

    assertThat(json).doesNotContain(CONTRIBUTOR);
    assertThat(slack).doesNotContain(CONTRIBUTOR);
    assertThat(teams).doesNotContain(CONTRIBUTOR);
    assertThat(slack).contains("hotspot", "src/A.java");
  }

  @Test
  void theStoredDigestHasNoFieldThatCouldHoldAPerson() {
    String[] banned = {"contributor", "author", "committer", "email", "user", "pseudonym"};
    for (Class<?> type :
        new Class<?>[] {
          DigestMessage.class,
          DigestMessage.Notable.class,
          DigestMessage.Expiring.class,
          DigestMessage.StageLine.class
        }) {
      assertThat(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName))
          .as(type.getSimpleName())
          .noneMatch(n -> Arrays.stream(banned).anyMatch(b -> n.toLowerCase().contains(b)));
    }
  }
}
