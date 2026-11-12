package com.debthunter.controlplane.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.intake.DurableRunIntakePublisher;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelopeCodec;
import com.debthunter.controlplane.ingest.intake.IntakeQueue;
import com.debthunter.controlplane.store.queue.AesGcmPayloadCipher;
import com.debthunter.controlplane.store.queue.JdbcWorkQueue;
import com.debthunter.controlplane.store.registry.JdbcRepositoryRegistry;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * CP-AC-04: when the database behind the intake queue cannot be reached the answer is 503 —
 * promptly. This is the constraint behind the whole control plane: a dead dependency must become a
 * fast refusal the pipeline shrugs off, never a hang the pipeline waits on. Uses the real durable
 * publisher, and the real registry, against an address nothing is listening on.
 */
@Tag("integration")
class CpAc04_QueueUnavailableReturns503Test {

  private static final Duration BOUND = Duration.ofSeconds(2);

  /** A database nothing is listening on. */
  private static DataSource deadDatabase() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl("jdbc:postgresql://127.0.0.1:1/none");
    dataSource.setUser("none");
    dataSource.setPassword("none");
    dataSource.setConnectTimeout(1);
    return dataSource;
  }

  private static DurableRunIntakePublisher publisherOver(DataSource dataSource) {
    byte[] key = new byte[32];
    return new DurableRunIntakePublisher(
        new JdbcWorkQueue(
            dataSource,
            IntakeQueue.policies(
                5, Duration.ofSeconds(30), Duration.ofMillis(100), Duration.ofSeconds(1)),
            Clock.systemUTC()),
        new AesGcmPayloadCipher(key),
        new IntakeEnvelopeCodec());
  }

  @Test
  void cpAc04_anUnreachableQueueReturns503WithinABoundedTime() throws Exception {
    try (IngestionHarness harness =
        IngestionHarness.builder().queue(publisherOver(deadDatabase())).start()) {
      long started = System.nanoTime();

      harness
          .publish(harness.tokenFor(IngestionHarness.SERVICE), RunBundleFixtures.validV1())
          .expectStatus()
          .isEqualTo(503)
          .expectHeader()
          .exists("Retry-After")
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("intake_unavailable");

      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(BOUND.multipliedBy(5));
    }
  }

  @Test
  void cpAc04_anUnreachableRegistryAlsoReturns503WithinABoundedTime() throws Exception {
    RepositoryRegistryService registry =
        new RepositoryRegistryService(new JdbcRepositoryRegistry(deadDatabase()));
    try (IngestionHarness harness = IngestionHarness.builder().scopeLookup(registry).start()) {
      long started = System.nanoTime();

      harness
          .publish(harness.tokenFor(IngestionHarness.SERVICE), RunBundleFixtures.validV1())
          .expectStatus()
          .isEqualTo(503)
          .expectHeader()
          .exists("Retry-After")
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("registry_unavailable");

      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(BOUND.multipliedBy(5));
    }
  }
}
