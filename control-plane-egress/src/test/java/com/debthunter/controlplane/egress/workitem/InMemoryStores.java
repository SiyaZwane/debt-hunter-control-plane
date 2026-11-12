package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.egress.MutableClock;
import com.debthunter.controlplane.egress.outbox.InMemoryEgressCheckpoints;
import com.debthunter.controlplane.store.findings.InMemoryPseudonymisedFindingStore;
import com.debthunter.controlplane.store.lifecycle.InMemoryTransitionLogRepository;
import com.debthunter.controlplane.store.queue.InMemoryWorkQueue;
import com.debthunter.controlplane.store.queue.QueuePolicy;
import com.debthunter.controlplane.store.registry.InMemoryRepositoryRegistry;
import com.debthunter.controlplane.store.suppressions.InMemorySuppressionRepository;
import java.time.Duration;
import java.util.Map;

/** Stores that live in memory, for unit tests. */
public final class InMemoryStores {

  /** Three attempts, a five-minute lease, backoff from one second to eight. */
  public static final Map<String, QueuePolicy> POLICIES =
      WorkItemQueue.policies(
          3, Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(8));

  private InMemoryStores() {}

  public static WorkItemHarness.Stores create(MutableClock clock) {
    return new WorkItemHarness.Stores(
        new InMemoryTransitionLogRepository(),
        new InMemoryPseudonymisedFindingStore(),
        new InMemorySuppressionRepository(),
        new InMemoryRepositoryRegistry(),
        new InMemoryWorkItemLinkRepository(),
        new InMemoryEgressCheckpoints(),
        new InMemoryWorkQueue(POLICIES, clock));
  }

  /** A harness over in-memory stores. */
  public static WorkItemHarness harness() {
    // The harness owns the clock, but the queue needs it first: share one.
    MutableClock clock = new MutableClock();
    return new WorkItemHarness(create(clock), clock);
  }
}
