package com.debthunter.controlplane.ingest.intake;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Publishes bundles to a Kafka topic, keyed by repository so one repository's bundles stay in order
 * on one partition.
 *
 * <p>Every wait is bounded by the configured limit, so a dead or unreachable broker turns into a
 * prompt {@link QueueUnavailableException} rather than a hung request.
 */
public final class KafkaRunIntakeQueuePublisher implements RunIntakeQueuePublisher, AutoCloseable {

  private final Producer<String, String> producer;
  private final String topic;
  private final IntakeEnvelopeCodec codec;

  /**
   * Creates a publisher over an existing producer.
   *
   * @param producer the Kafka producer to send with
   * @param topic the intake topic
   * @param codec encodes envelopes for the wire
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "the producer is a shared, thread-safe client the caller owns the lifecycle of")
  public KafkaRunIntakeQueuePublisher(
      Producer<String, String> producer, String topic, IntakeEnvelopeCodec codec) {
    this.producer = producer;
    this.topic = topic;
    this.codec = codec;
  }

  /**
   * Connects to a Kafka cluster with every wait bounded by {@code maxWait}.
   *
   * @param bootstrapServers the cluster's bootstrap addresses
   * @param topic the intake topic
   * @param maxWait the longest any single publish may block or wait for acknowledgement
   * @return a publisher owning its own producer
   */
  public static KafkaRunIntakeQueuePublisher connect(
      String bootstrapServers, String topic, Duration maxWait) {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
    props.put(ProducerConfig.LINGER_MS_CONFIG, "0");
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(maxWait.toMillis()));
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(maxWait.toMillis()));
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(maxWait.toMillis() * 2));
    return new KafkaRunIntakeQueuePublisher(
        new KafkaProducer<>(props), topic, new IntakeEnvelopeCodec());
  }

  @Override
  public CompletionStage<Void> publish(IntakeEnvelope envelope) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    String value = codec.encode(envelope);
    try {
      producer.send(
          new ProducerRecord<>(topic, envelope.repository(), value),
          (metadata, failure) -> {
            if (failure == null) {
              result.complete(null);
            } else {
              result.completeExceptionally(
                  new QueueUnavailableException("The intake queue rejected the bundle", failure));
            }
          });
    } catch (KafkaException | IllegalStateException e) {
      result.completeExceptionally(
          new QueueUnavailableException("The intake queue could not be reached", e));
    }
    return result;
  }

  @Override
  public void close() {
    producer.close(Duration.ofSeconds(5));
  }
}
