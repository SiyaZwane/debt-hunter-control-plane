package com.debthunter.controlplane.egress.workitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.registry.WorkItemRoute;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JiraWorkItemAdapterTest {

  private static final WorkItemRoute ROUTE = new WorkItemRoute("jira", "DH");
  private static final String KEY = "dh-0123456789abcdef0123456789abcdef";

  private FakeJiraServer jira;
  private JiraWorkItemAdapter adapter;

  @BeforeEach
  void setUp() {
    jira = new FakeJiraServer();
    adapter = adapterFor(jira);
  }

  @AfterEach
  void tearDown() {
    jira.close();
  }

  private static JiraWorkItemAdapter adapterFor(FakeJiraServer server) {
    return new JiraWorkItemAdapter(
        server.uri(),
        () -> "Bearer secret-token",
        Duration.ofSeconds(5),
        JiraWorkItemAdapter.StateNames.defaults());
  }

  private static WorkItemContent content(String message) {
    return new WorkItemContent(
        KEY,
        "Technical debt: rule in src/Foo.java",
        List.of(
            new WorkItemContent.Detail("Rule", "codemaat.hotspot"),
            new WorkItemContent.Detail("What was found", message)),
        "https://cp.example/runs/r1");
  }

  @Test
  void createsAnItemInTheRoutesProjectCarryingItsKeyAsALabel() {
    TrackedItem item = adapter.create(ROUTE, content("a hotspot"));

    assertThat(item.id()).isEqualTo("DH-1");
    assertThat(item.url()).isEqualTo(jira.uri() + "/browse/DH-1");
    assertThat(item.status()).isEqualTo(WorkItemStatus.OPEN);
    FakeJiraServer.Issue issue = jira.issues.get("DH-1");
    assertThat(issue.project).isEqualTo("DH");
    assertThat(issue.labels).containsExactly(KEY, "debt-hunter");
    assertThat(issue.description).contains("codemaat.hotspot", "a hotspot", "cp.example/runs/r1");
  }

  @Test
  void findsAnItemByItsKeyAndReportsWhereItStands() {
    adapter.create(ROUTE, content("a hotspot"));

    TrackedItem found = adapter.find(ROUTE, KEY).orElseThrow();

    assertThat(found.id()).isEqualTo("DH-1");
    assertThat(found.status()).isEqualTo(WorkItemStatus.OPEN);
    assertThat(adapter.find(ROUTE, "dh-other")).isEmpty();
  }

  @Test
  void anItemInAStateTheControlPlaneDoesNotSetHasNoStatus() {
    adapter.create(ROUTE, content("x"));
    jira.issues.get("DH-1").status = "In Review";

    assertThat(adapter.find(ROUTE, KEY).orElseThrow().status()).isNull();
  }

  @Test
  void movesAnItemThroughTheWorkflowAndComments() {
    adapter.create(ROUTE, content("x"));

    adapter.move(ROUTE, "DH-1", WorkItemStatus.CLOSED, "Closed by Debt Hunter");
    assertThat(jira.issues.get("DH-1").status).isEqualTo("Done");
    adapter.move(ROUTE, "DH-1", WorkItemStatus.ON_HOLD, "On hold");
    assertThat(jira.issues.get("DH-1").status).isEqualTo("On Hold");
    adapter.move(ROUTE, "DH-1", WorkItemStatus.OPEN, "Reopened");
    assertThat(jira.issues.get("DH-1").status).isEqualTo("To Do");

    assertThat(jira.issues.get("DH-1").comments)
        .containsExactly("Closed by Debt Hunter", "On hold", "Reopened");
  }

  @Test
  void movingAnItemToWhereItAlreadyIsDoesNotCallTheWorkflowAgain() {
    adapter.create(ROUTE, content("x"));
    jira.requests.clear();

    adapter.move(ROUTE, "DH-1", WorkItemStatus.OPEN, "still open");

    assertThat(jira.requests).noneMatch(r -> r.path().contains("/transitions"));
    assertThat(jira.issues.get("DH-1").comments).containsExactly("still open");
  }

  @Test
  void aWorkflowWithNoRouteToTheStateIsARefusalNotAnOutage() {
    adapter.create(ROUTE, content("x"));
    jira.reachable = List.of("To Do");

    assertThatThrownBy(() -> adapter.move(ROUTE, "DH-1", WorkItemStatus.CLOSED, "c"))
        .isInstanceOf(TrackerRejectedException.class)
        .hasMessageContaining("Done");
  }

  @Test
  void untrustedTextCannotBecomeMarkupMentionsOrLinks() {
    adapter.create(
        ROUTE, content("{code}boom{code} [~admin] [click|http://evil] !http://evil/x.png!"));

    String description = jira.issues.get("DH-1").description;
    String found = description.substring(description.indexOf("What was found"));
    assertThat(found).doesNotContain("[~admin]", "[click", "!http", "{code}");
    assertThat(found).contains("boom");
  }

  @Test
  void aCommentCannotCarryMarkupEither() {
    adapter.create(ROUTE, content("x"));
    adapter.move(ROUTE, "DH-1", WorkItemStatus.ON_HOLD, "Reason: [~admin] {panel}");

    assertThat(jira.issues.get("DH-1").comments.get(0)).doesNotContain("[~admin]", "{panel}");
  }

  @Test
  void theCredentialIsSentInTheHeaderOnEveryCallAndNowhereElse() {
    adapter.create(ROUTE, content("x"));
    adapter.find(ROUTE, KEY);

    assertThat(jira.requests).isNotEmpty();
    assertThat(jira.requests)
        .allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer secret-token"));
    assertThat(jira.requests)
        .allSatisfy(r -> assertThat(r.path() + r.body()).doesNotContain("secret-token"));
  }

  @Test
  void aRedirectIsNotFollowedSoTheCredentialGoesNowhereElse() {
    jira.redirectTo = "http://127.0.0.1:1/steal";

    assertThatThrownBy(() -> adapter.find(ROUTE, KEY)).isInstanceOf(TrackerRejectedException.class);
    assertThat(jira.requests).hasSize(1);
  }

  @Test
  void anOutageARateLimitAndRefusedCredentialsAreAllUnavailableNotRefusals() {
    for (int status : new int[] {429, 500, 503, 401, 403}) {
      jira.forcedStatus = status;
      assertThatThrownBy(() -> adapter.find(ROUTE, KEY))
          .as("HTTP %d", status)
          .isInstanceOf(TrackerUnavailableException.class);
    }
  }

  @Test
  void aTrackerThatCannotBeReachedIsUnavailable() {
    FakeJiraServer gone = new FakeJiraServer();
    JiraWorkItemAdapter orphan = adapterFor(gone);
    gone.close();

    assertThatThrownBy(() -> orphan.find(ROUTE, KEY))
        .isInstanceOf(TrackerUnavailableException.class);
  }

  @Test
  void aCredentialThatCannotBeObtainedIsUnavailableNotARefusal() {
    JiraWorkItemAdapter broken =
        new JiraWorkItemAdapter(
            jira.uri(),
            () -> {
              throw new IllegalStateException("vault down");
            },
            Duration.ofSeconds(5),
            JiraWorkItemAdapter.StateNames.defaults());

    assertThatThrownBy(() -> broken.find(ROUTE, KEY))
        .isInstanceOf(TrackerUnavailableException.class)
        .hasMessageNotContaining("vault");
  }

  @Test
  void aRouteTargetThatCouldInjectQueryTextIsRefusedBeforeAnyCall() {
    WorkItemRoute evil = new WorkItemRoute("jira", "DH\" OR project = \"X");

    assertThatThrownBy(() -> adapter.find(evil, KEY)).isInstanceOf(TrackerRejectedException.class);
    assertThat(jira.requests).isEmpty();
  }

  @Test
  void anItemIdOfAnUnexpectedFormIsNeverPutInAnAddress() {
    assertThatThrownBy(() -> adapter.move(ROUTE, "../../admin", WorkItemStatus.CLOSED, "c"))
        .isInstanceOf(TrackerRejectedException.class);
    assertThat(jira.requests).isEmpty();
  }

  @Test
  void aProjectThatDoesNotExistIsARefusal() {
    assertThatThrownBy(() -> adapter.move(ROUTE, "DH-99", WorkItemStatus.CLOSED, "c"))
        .isInstanceOf(TrackerRejectedException.class);
  }

  @Test
  void stateNamesMatchWithoutRegardToCase() {
    assertThat(new JiraWorkItemAdapter.StateNames("a", "b", "c").statusOf("B"))
        .isEqualTo(WorkItemStatus.ON_HOLD);
    assertThat(new JiraWorkItemAdapter.StateNames("a", "b", "c").statusOf(null)).isNull();
  }
}
