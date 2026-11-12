package com.debthunter.controlplane.egress.workitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.registry.WorkItemRoute;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AzureBoardsWorkItemAdapterTest {

  private static final WorkItemRoute ROUTE = new WorkItemRoute("azure-boards", "Platform Team");
  private static final String KEY = "dh-0123456789abcdef0123456789abcdef";

  private FakeAzureDevOpsServer azure;
  private AzureBoardsWorkItemAdapter adapter;

  @BeforeEach
  void setUp() {
    azure = new FakeAzureDevOpsServer();
    adapter = adapterFor(azure);
  }

  @AfterEach
  void tearDown() {
    azure.close();
  }

  private static AzureBoardsWorkItemAdapter adapterFor(FakeAzureDevOpsServer server) {
    return new AzureBoardsWorkItemAdapter(
        server.uri(),
        () -> "Basic c2VjcmV0",
        Duration.ofSeconds(5),
        AzureBoardsWorkItemAdapter.StateNames.defaults());
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
  void createsAWorkItemInTheRoutesProjectCarryingItsKeyAsATag() {
    TrackedItem item = adapter.create(ROUTE, content("a hotspot"));

    assertThat(item.id()).isEqualTo("41");
    assertThat(item.url()).isEqualTo(azure.uri() + "/Platform%20Team/_workitems/edit/41");
    assertThat(item.status()).isEqualTo(WorkItemStatus.OPEN);
    FakeAzureDevOpsServer.WorkItem w = azure.items.get(41);
    assertThat(w.project).isEqualTo("Platform Team");
    assertThat(w.fields.get("System.Tags")).isEqualTo(KEY + "; debt-hunter");
    assertThat(w.fields.get("System.Description")).contains("codemaat.hotspot", "a hotspot");
    assertThat(azure.requests.get(0).contentType()).isEqualTo("application/json-patch+json");
  }

  @Test
  void findsAWorkItemByItsKeyAndReportsWhereItStands() {
    adapter.create(ROUTE, content("x"));
    azure.items.get(41).fields.put("System.State", "Closed");

    TrackedItem found = adapter.find(ROUTE, KEY).orElseThrow();

    assertThat(found.id()).isEqualTo("41");
    assertThat(found.status()).isEqualTo(WorkItemStatus.CLOSED);
    assertThat(adapter.find(ROUTE, "dh-other")).isEmpty();
  }

  @Test
  void movesAWorkItemBetweenStatesAndComments() {
    adapter.create(ROUTE, content("x"));

    adapter.move(ROUTE, "41", WorkItemStatus.CLOSED, "Closed by Debt Hunter");
    assertThat(azure.items.get(41).fields.get("System.State")).isEqualTo("Closed");
    adapter.move(ROUTE, "41", WorkItemStatus.ON_HOLD, "On hold");
    assertThat(azure.items.get(41).fields.get("System.State")).isEqualTo("On Hold");
    adapter.move(ROUTE, "41", WorkItemStatus.OPEN, "Reopened");
    assertThat(azure.items.get(41).fields.get("System.State")).isEqualTo("Active");

    assertThat(azure.items.get(41).comments)
        .containsExactly("Closed by Debt Hunter", "On hold", "Reopened");
  }

  @Test
  void movingAWorkItemToWhereItAlreadyIsMakesNoStateChange() {
    adapter.create(ROUTE, content("x"));
    adapter.move(ROUTE, "41", WorkItemStatus.CLOSED, "c1");
    azure.requests.clear();

    adapter.move(ROUTE, "41", WorkItemStatus.CLOSED, "c2");

    assertThat(azure.requests).noneMatch(r -> r.method().equals("PATCH"));
    assertThat(azure.items.get(41).comments).containsExactly("c1", "c2");
  }

  @Test
  void untrustedTextIsHtmlEscapedSoItCannotBecomeMarkup() {
    adapter.create(ROUTE, content("<script>alert(1)</script> & <img src=x onerror=y>"));

    String html = azure.items.get(41).fields.get("System.Description");
    assertThat(html).doesNotContain("<script>", "<img");
    assertThat(html).contains("&lt;script&gt;", "&amp;");
  }

  @Test
  void aCommentIsHtmlEscapedToo() {
    adapter.create(ROUTE, content("x"));
    adapter.move(ROUTE, "41", WorkItemStatus.ON_HOLD, "Reason: <b>bold</b>");

    assertThat(azure.items.get(41).comments.get(0)).doesNotContain("<b>").contains("&lt;b&gt;");
  }

  @Test
  void theCredentialIsSentInTheHeaderOnEveryCallAndNowhereElse() {
    adapter.create(ROUTE, content("x"));
    adapter.find(ROUTE, KEY);

    assertThat(azure.requests)
        .allSatisfy(
            r -> {
              assertThat(r.authorization()).isEqualTo("Basic c2VjcmV0");
              assertThat(r.path() + r.body()).doesNotContain("c2VjcmV0");
            });
  }

  @Test
  void aRedirectIsNotFollowed() {
    azure.redirectTo = "http://127.0.0.1:1/steal";

    assertThatThrownBy(() -> adapter.find(ROUTE, KEY)).isInstanceOf(TrackerRejectedException.class);
    assertThat(azure.requests).hasSize(1);
  }

  @Test
  void anOutageARateLimitAndRefusedCredentialsAreAllUnavailableNotRefusals() {
    for (int status : new int[] {429, 500, 503, 401, 403}) {
      azure.forcedStatus = status;
      assertThatThrownBy(() -> adapter.find(ROUTE, KEY))
          .as("HTTP %d", status)
          .isInstanceOf(TrackerUnavailableException.class);
    }
  }

  @Test
  void aTrackerThatCannotBeReachedIsUnavailable() {
    FakeAzureDevOpsServer gone = new FakeAzureDevOpsServer();
    AzureBoardsWorkItemAdapter orphan = adapterFor(gone);
    gone.close();

    assertThatThrownBy(() -> orphan.create(ROUTE, content("x")))
        .isInstanceOf(TrackerUnavailableException.class);
  }

  @Test
  void aProjectNameThatCouldChangeTheAddressIsRefusedBeforeAnyCall() {
    WorkItemRoute evil = new WorkItemRoute("azure-boards", "../other");

    assertThatThrownBy(() -> adapter.find(evil, KEY)).isInstanceOf(TrackerRejectedException.class);
    assertThat(azure.requests).isEmpty();
  }

  @Test
  void anIdThatIsNotANumberIsNeverPutInAnAddress() {
    assertThatThrownBy(() -> adapter.move(ROUTE, "41/../../x", WorkItemStatus.CLOSED, "c"))
        .isInstanceOf(TrackerRejectedException.class);
    assertThat(azure.requests).isEmpty();
  }

  @Test
  void aKeyThatCouldBreakTheQueryIsRefused() {
    assertThatThrownBy(() -> adapter.find(ROUTE, "x' OR 1=1 --"))
        .isInstanceOf(TrackerRejectedException.class);
    assertThat(azure.requests).isEmpty();
  }

  @Test
  void anUnknownWorkItemIsARefusal() {
    assertThatThrownBy(() -> adapter.move(ROUTE, "999", WorkItemStatus.CLOSED, "c"))
        .isInstanceOf(TrackerRejectedException.class);
  }

  @Test
  void stateNamesMatchWithoutRegardToCase() {
    assertThat(new AzureBoardsWorkItemAdapter.StateNames("a", "b", "c").statusOf("C"))
        .isEqualTo(WorkItemStatus.CLOSED);
    assertThat(new AzureBoardsWorkItemAdapter.StateNames("a", "b", "c").statusOf(null)).isNull();
    assertThat(new AzureBoardsWorkItemAdapter.StateNames("a", "b", "c").statusOf("zzz")).isNull();
  }
}
