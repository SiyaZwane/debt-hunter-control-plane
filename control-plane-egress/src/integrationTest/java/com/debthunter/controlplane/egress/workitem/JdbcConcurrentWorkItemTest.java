package com.debthunter.controlplane.egress.workitem;

import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.REPO;
import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.SLICE;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.egress.outbox.DeliveryUnavailableException;
import com.debthunter.domain.Severity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The same guarantees as in memory, with the claim decided by PostgreSQL. */
@Tag("integration")
class JdbcConcurrentWorkItemTest {

  private static final int WORKERS = 8;

  @Test
  void onlyOneOfManyWorkersGetsTheClaimOnAFinding() throws Exception {
    WorkItemHarness h = JdbcStores.harness();
    Instant now = h.clock.instant();
    h.stores.links().reserve(REPO, SLICE, "fp-1", "k", "fake", now);
    ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < WORKERS; i++) {
      String owner = "w" + i;
      results.add(
          pool.submit(
              () -> {
                go.await();
                return h.stores.links().claim(REPO, SLICE, "fp-1", owner, now, now.plusSeconds(60));
              }));
    }
    go.countDown();
    int winners = 0;
    for (Future<Boolean> f : results) {
      if (f.get(10, TimeUnit.SECONDS)) {
        winners++;
      }
    }
    pool.shutdown();

    assertThat(winners).isEqualTo(1);
  }

  @Test
  void manyWorkersCreatingTheSameItemOnPostgresCreateOne() throws Exception {
    WorkItemHarness h = JdbcStores.harness();
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    h.tracker.duringCreate =
        () -> {
          try {
            Thread.sleep(100);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Object>> results = new ArrayList<>();
    for (int i = 0; i < WORKERS; i++) {
      WorkItemOrchestrator worker =
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
      results.add(
          pool.submit(
              () -> {
                go.await();
                try {
                  return worker.reconcile(new SyncJob(REPO, SLICE, "fp-1", "run-1", "e"));
                } catch (DeliveryUnavailableException e) {
                  return e;
                }
              }));
    }
    go.countDown();
    for (Future<Object> f : results) {
      f.get(20, TimeUnit.SECONDS);
    }
    pool.shutdown();

    assertThat(h.tracker.creates.get()).isEqualTo(1);
    assertThat(h.tracker.all()).hasSize(1);
  }
}
