package com.debthunter.controlplane.egress.workitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What every link repository must do. */
public abstract class WorkItemLinkRepositoryContract {

  private static final Instant T0 = Instant.parse("2026-07-01T12:00:00Z");

  protected abstract WorkItemLinkRepository newRepository();

  private WorkItemLinkRepository links;

  @BeforeEach
  void setUp() {
    links = newRepository();
  }

  private WorkItemLink reserve(String fp) {
    return links.reserve("acme/widgets", "root", fp, "dh-key-" + fp, "jira", T0);
  }

  @Test
  void aFindingHasNoLinkUntilOneIsReserved() {
    assertThat(links.find("acme/widgets", "root", "fp")).isEmpty();
  }

  @Test
  void reservingRecordsAnItemlessLink() {
    WorkItemLink link = reserve("fp");

    assertThat(link.hasItem()).isFalse();
    assertThat(link.itemKey()).isEqualTo("dh-key-fp");
    assertThat(link.tracker()).isEqualTo("jira");
    assertThat(link.status()).isNull();
    assertThat(links.find("acme/widgets", "root", "fp")).contains(link);
  }

  @Test
  void reservingTwiceLeavesWhatIsThere() {
    WorkItemLink first = reserve("fp");
    links.save(
        first.withItem(
            new TrackedItem("DH-1", "https://x/DH-1", WorkItemStatus.OPEN),
            WorkItemStatus.OPEN,
            T0.plusSeconds(1)));

    WorkItemLink again =
        links.reserve("acme/widgets", "root", "fp", "different-key", "azure", T0.plusSeconds(9));

    assertThat(again.itemId()).isEqualTo("DH-1");
    assertThat(again.itemKey()).isEqualTo("dh-key-fp");
    assertThat(again.tracker()).isEqualTo("jira");
  }

  @Test
  void aSavedItemAndStatusAreReadBack() {
    WorkItemLink link = reserve("fp");
    TrackedItem item = new TrackedItem("DH-7", "https://x/DH-7", WorkItemStatus.OPEN);

    links.save(link.withItem(item, WorkItemStatus.OPEN, T0.plusSeconds(5)));
    links.save(
        links
            .find("acme/widgets", "root", "fp")
            .orElseThrow()
            .withStatus(WorkItemStatus.CLOSED, T0.plusSeconds(6)));

    WorkItemLink read = links.find("acme/widgets", "root", "fp").orElseThrow();
    assertThat(read.itemId()).isEqualTo("DH-7");
    assertThat(read.itemUrl()).isEqualTo("https://x/DH-7");
    assertThat(read.status()).isEqualTo(WorkItemStatus.CLOSED);
    assertThat(read.updatedAt()).isEqualTo(T0.plusSeconds(6));
  }

  @Test
  void savingALinkNobodyReservedIsRefused() {
    WorkItemLink stray =
        new WorkItemLink("acme/widgets", "root", "ghost", "k", "jira", null, null, null, T0);

    assertThatThrownBy(() -> links.save(stray)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aFindingsLinkIsItsOwn() {
    reserve("a");
    reserve("b");

    assertThat(links.find("acme/widgets", "root", "a").orElseThrow().itemKey())
        .isEqualTo("dh-key-a");
    assertThat(links.find("acme/widgets", "root", "b").orElseThrow().itemKey())
        .isEqualTo("dh-key-b");
    assertThat(links.find("acme/widgets", "other-slice", "a")).isEmpty();
  }

  @Test
  void onlyOneWorkerCanHoldAFindingAtATime() {
    reserve("fp");

    assertThat(links.claim("acme/widgets", "root", "fp", "w1", T0, T0.plusSeconds(60))).isTrue();
    assertThat(
            links.claim("acme/widgets", "root", "fp", "w2", T0.plusSeconds(1), T0.plusSeconds(61)))
        .isFalse();
  }

  @Test
  void aClaimLapsesAndCanBeReleased() {
    reserve("fp");
    links.claim("acme/widgets", "root", "fp", "w1", T0, T0.plusSeconds(60));

    assertThat(
            links.claim(
                "acme/widgets", "root", "fp", "w2", T0.plusSeconds(60), T0.plusSeconds(120)))
        .as("expired claims can be taken over")
        .isTrue();

    links.release("acme/widgets", "root", "fp", "w1");
    assertThat(
            links.claim(
                "acme/widgets", "root", "fp", "w3", T0.plusSeconds(61), T0.plusSeconds(121)))
        .as("w1 no longer holds it, so its release changes nothing")
        .isFalse();

    links.release("acme/widgets", "root", "fp", "w2");
    assertThat(
            links.claim(
                "acme/widgets", "root", "fp", "w3", T0.plusSeconds(61), T0.plusSeconds(121)))
        .isTrue();
  }

  @Test
  void aFindingWithNoLinkCannotBeClaimed() {
    assertThat(links.claim("acme/widgets", "root", "ghost", "w1", T0, T0.plusSeconds(60)))
        .isFalse();
  }

  @Test
  void aClaimOnOneFindingDoesNotBlockAnother() {
    reserve("a");
    reserve("b");
    links.claim("acme/widgets", "root", "a", "w1", T0, T0.plusSeconds(60));

    assertThat(links.claim("acme/widgets", "root", "b", "w2", T0, T0.plusSeconds(60))).isTrue();
  }
}
