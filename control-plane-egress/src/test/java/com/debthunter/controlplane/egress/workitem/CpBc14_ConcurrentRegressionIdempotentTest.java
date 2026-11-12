package com.debthunter.controlplane.egress.workitem;

import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.REPO;
import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.SLICE;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.egress.outbox.DeliveryUnavailableException;
import com.debthunter.domain.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * CP-BC-14: two workers acting on the same regression at the same moment must leave one item,
 * reopened once. The one that does not get the finding waits; it never writes a second time.
 */
class CpBc14_ConcurrentRegressionIdempotentTest {

  private static final String FP = "fp-1";
  private static final int WORKERS = 8;

  private WorkItemHarness regressedFinding() {
    WorkItemHarness h = InMemoryStores.harness();
    h.run("run-1", WorkItemHarness.finding(FP, Severity.HIGH));
    h.firstSeen(FP, "run-1");
    h.opened(FP, "run-1");
    h.orchestrator.reconcile(new SyncJob(REPO, SLICE, FP, "run-1", "e1"));
    h.resolved(FP, "run-2");
    h.orchestrator.reconcile(new SyncJob(REPO, SLICE, FP, "run-1", "e2"));
    h.regressed(FP, "run-3");
    return h;
  }

  /** Runs the same job on many threads released together. */
  private static List<Object> race(WorkItemHarness h, WorkItemOrchestrator[] workers)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    for (WorkItemOrchestrator worker : workers) {
      futures.add(
          pool.submit(
              () -> {
                go.await();
                try {
                  return worker.reconcile(new SyncJob(REPO, SLICE, FP, "run-1", "e3"));
                } catch (DeliveryUnavailableException e) {
                  return e;
                }
              }));
    }
    go.countDown();
    List<Object> outcomes = new ArrayList<>();
    for (Future<Object> f : futures) {
      outcomes.add(f.get(10, TimeUnit.SECONDS));
    }
    pool.shutdown();
    return outcomes;
  }

  private static WorkItemOrchestrator[] workers(WorkItemHarness h) {
    WorkItemOrchestrator[] workers = new WorkItemOrchestrator[WORKERS];
    for (int i = 0; i < WORKERS; i++) {
      workers[i] =
          new WorkItemOrchestrator(
              h.stores.log(),
              h.stores.links(),
              h.stores.findings(),
              h.stores.suppressions(),
              h.stores.registry(),
              () -> h.stages,
              new WorkItemTrackers(java.util.Map.of(WorkItemHarness.ROUTE_TRACKER, h.tracker)),
              RunLinks.none(),
              h.settings,
              h.clock,
              "worker-" + i);
    }
    return workers;
  }

  @Test
  void manyWorkersReopeningTheSameRegressionReopenItOnce() throws Exception {
    WorkItemHarness h = regressedFinding();
    int movesBefore = h.tracker.moves.get();

    List<Object> outcomes = race(h, workers(h));

    assertThat(h.tracker.all()).hasSize(1);
    assertThat(h.tracker.moves.get() - movesBefore).as("reopened exactly once").isEqualTo(1);
    assertThat(h.tracker.all().get(0).status).isEqualTo(WorkItemStatus.OPEN);
    assertThat(outcomes)
        .as("each worker did the reopening, found it done, or waited its turn")
        .allSatisfy(
            o ->
                assertThat(o)
                    .satisfiesAnyOf(
                        x -> assertThat(x).isEqualTo(SyncResult.REOPENED),
                        x -> assertThat(x).isEqualTo(SyncResult.NO_ACTION),
                        x -> assertThat(x).isInstanceOf(DeliveryUnavailableException.class)));
    assertThat(outcomes.stream().filter(SyncResult.REOPENED::equals).count()).isEqualTo(1);
  }

  @Test
  void manyWorkersCreatingTheSameFirstItemCreateOne() throws Exception {
    WorkItemHarness h = InMemoryStores.harness();
    h.run("run-1", WorkItemHarness.finding(FP, Severity.HIGH));
    h.firstSeen(FP, "run-1");
    h.opened(FP, "run-1");
    h.tracker.duringCreate =
        () -> {
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };

    race(h, workers(h));

    assertThat(h.tracker.creates.get()).isEqualTo(1);
    assertThat(h.tracker.all()).hasSize(1);
  }
}
