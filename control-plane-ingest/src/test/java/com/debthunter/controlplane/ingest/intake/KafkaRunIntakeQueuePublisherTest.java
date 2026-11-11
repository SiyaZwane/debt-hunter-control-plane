package com.debthunter.controlplane.ingest.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaRunIntakeQueuePublisherTest {

  private static final String TOPIC = "run-intake";

  private final IntakeEnvelopeCodec codec = new IntakeEnvelopeCodec();

  @Test
  void anAcknowledgedBundleCompletesTheStageNormally() {
    MockProducer<String, String> producer =
        new MockProducer<>(false, new StringSerializer(), new StringSerializer());
    KafkaRunIntakeQueuePublisher publisher =
        new KafkaRunIntakeQueuePublisher(producer, TOPIC, codec);

    CompletableFuture<Void> stage =
        publisher.publish(IntakeEnvelopes.forRun("run-1")).toCompletableFuture();
    assertThat(stage).isNotDone();
    producer.completeNext();

    assertThat(stage).isCompleted();
  }

  @Test
  void aBundleIsKeyedByRepositoryAndCarriesTheEncodedEnvelope() {
    MockProducer<String, String> producer =
        new MockProducer<>(true, new StringSerializer(), new StringSerializer());
    KafkaRunIntakeQueuePublisher publisher =
        new KafkaRunIntakeQueuePublisher(producer, TOPIC, codec);
    IntakeEnvelope envelope = IntakeEnvelopes.forRun("run-1", "acme/gadgets");

    publisher.publish(envelope);

    assertThat(producer.history()).hasSize(1);
    ProducerRecord<String, String> record = producer.history().get(0);
    assertThat(record.topic()).isEqualTo(TOPIC);
    assertThat(record.key()).isEqualTo("acme/gadgets");
    assertThat(codec.decode(record.value())).isEqualTo(envelope);
  }

  @Test
  void aBrokerRejectionFailsTheStageWithQueueUnavailable() {
    MockProducer<String, String> producer =
        new MockProducer<>(false, new StringSerializer(), new StringSerializer());
    KafkaRunIntakeQueuePublisher publisher =
        new KafkaRunIntakeQueuePublisher(producer, TOPIC, codec);

    CompletableFuture<Void> stage =
        publisher.publish(IntakeEnvelopes.forRun("run-1")).toCompletableFuture();
    producer.errorNext(new RuntimeException("broker said no"));

    assertThat(stage)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .havingCause()
        .isInstanceOf(QueueUnavailableException.class)
        .havingRootCause()
        .withMessage("broker said no");
  }

  @Test
  void aSendThatCannotEvenStartFailsTheStageWithQueueUnavailable() {
    @SuppressWarnings("unchecked")
    Producer<String, String> producer = mock(Producer.class);
    when(producer.send(any(), any())).thenThrow(new TimeoutException("metadata not available"));
    KafkaRunIntakeQueuePublisher publisher =
        new KafkaRunIntakeQueuePublisher(producer, TOPIC, codec);

    CompletableFuture<Void> stage =
        publisher.publish(IntakeEnvelopes.forRun("run-1")).toCompletableFuture();

    assertThat(stage)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .havingCause()
        .isInstanceOf(QueueUnavailableException.class);
  }

  @Test
  void closingThePublisherClosesItsProducer() {
    @SuppressWarnings("unchecked")
    Producer<String, String> producer = mock(Producer.class);

    new KafkaRunIntakeQueuePublisher(producer, TOPIC, codec).close();

    verify(producer).close(any(Duration.class));
  }
}
