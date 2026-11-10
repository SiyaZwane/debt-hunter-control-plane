package com.debthunter.controlplane.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.intake.KafkaRunIntakeQueuePublisher;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-04: when the intake queue cannot be reached the answer is 503 — promptly. This is the
 * constraint behind the whole control plane: a dead dependency must become a fast refusal the
 * pipeline shrugs off, never a hang the pipeline waits on. Uses the real Kafka publisher against an
 * address nothing is listening on.
 */
@Tag("integration")
class CpAc04_QueueUnavailableReturns503Test {

  private static final Duration QUEUE_WAIT = Duration.ofSeconds(2);

  @Test
  void cpAc04_anUnreachableQueueReturns503WithinABoundedTime() throws Exception {
    try (KafkaRunIntakeQueuePublisher deadQueue =
            KafkaRunIntakeQueuePublisher.connect("127.0.0.1:1", "run-intake", QUEUE_WAIT);
        IngestionHarness harness = IngestionHarness.builder().queue(deadQueue).start()) {
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

      Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
      assertThat(elapsed).isLessThan(QUEUE_WAIT.multipliedBy(5));
    }
  }
}
