package com.debthunter.controlplane.egress.workitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.egress.outbox.DeliveryUnavailableException;
import com.debthunter.controlplane.egress.outbox.OutboxMessage;
import com.debthunter.controlplane.egress.outbox.UndeliverableException;
import com.debthunter.controlplane.store.jdbc.StoreException;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.store.lifecycle.InMemoryTransitionLogRepository;
import com.debthunter.controlplane.store.lifecycle.SequencedTransition;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkItemSyncHandlerTest {

  /** A log whose store is down. */
  private static final class DownLog implements TransitionLogRepository {
    @Override
    public void append(List<FindingTransitionEvent> events) {
      throw new StoreException("down", null);
    }

    @Override
    public List<SequencedTransition> after(long afterSeq, int limit) {
      throw new StoreException("down", null);
    }

    @Override
    public List<FindingTransitionEvent> historyOf(String r, String s, String f) {
      throw new StoreException("down", null);
    }

    @Override
    public List<FindingTransitionEvent> eventsOfRun(String runId) {
      throw new StoreException("down", null);
    }
  }

  @Test
  void aMessageThatIsNotAJobCanNeverBeDelivered() {
    WorkItemHarness h = InMemoryStores.harness();
    WorkItemSyncHandler handler = new WorkItemSyncHandler(h.orchestrator);

    assertThatThrownBy(
            () ->
                handler.deliver(
                    new OutboxMessage(1, "k", "nope".getBytes(StandardCharsets.UTF_8), 1)))
        .isInstanceOf(UndeliverableException.class);
  }

  @Test
  void anOutageOfTheSystemOfRecordHoldsTheMessageItIsNotTheMessagesFault() {
    WorkItemHarness h = InMemoryStores.harness();
    WorkItemOrchestrator orchestrator =
        new WorkItemOrchestrator(
            new DownLog(),
            h.stores.links(),
            h.stores.findings(),
            h.stores.suppressions(),
            h.stores.registry(),
            () -> h.stages,
            new WorkItemTrackers(java.util.Map.of()),
            RunLinks.none(),
            h.settings,
            h.clock,
            "w");
    WorkItemSyncHandler handler = new WorkItemSyncHandler(orchestrator);
    byte[] job = new SyncJob("r", "s", "fp", "run", "e").encode();

    assertThatThrownBy(() -> handler.deliver(new OutboxMessage(1, "k", job, 1)))
        .isInstanceOf(DeliveryUnavailableException.class)
        .hasCauseInstanceOf(StoreException.class);
    assertThat(new InMemoryTransitionLogRepository().after(0, 1)).isEmpty();
  }

  @Test
  void aJobRoundTripsThroughItsBytes() {
    SyncJob job = new SyncJob("acme/widgets", "root", "fp", "run-1", "ev-1");

    assertThat(SyncJob.decode(job.encode())).isEqualTo(job);
    assertThatThrownBy(() -> SyncJob.decode(new byte[] {1, 2, 3}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> SyncJob.decode("{\"repository\":\"r\"}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
