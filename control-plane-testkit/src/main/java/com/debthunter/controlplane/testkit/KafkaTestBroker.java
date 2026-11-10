package com.debthunter.controlplane.testkit;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A single real Kafka broker, started lazily in a container and shared by every test in the JVM, so
 * the cost of starting it is paid once rather than per test class. Requires a running Docker
 * daemon.
 */
public final class KafkaTestBroker {

  private static final DockerImageName IMAGE = DockerImageName.parse("apache/kafka:3.8.0");

  private static KafkaContainer container;

  private KafkaTestBroker() {}

  /**
   * The broker's bootstrap address, starting the broker on first use.
   *
   * @return a {@code host:port} bootstrap address reachable from the test JVM
   */
  public static synchronized String bootstrapServers() {
    if (container == null) {
      KafkaContainer started = new KafkaContainer(IMAGE);
      started.start();
      Runtime.getRuntime().addShutdownHook(new Thread(started::stop, "stop-kafka-test-broker"));
      container = started;
    }
    return container.getBootstrapServers();
  }
}
