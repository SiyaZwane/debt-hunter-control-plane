package com.debthunter.controlplane.ingest;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;

/** Creates an isolated single-partition topic per test, so tests never see each other's records. */
final class KafkaTopics {

  private KafkaTopics() {}

  static String create(String bootstrapServers) throws Exception {
    String topic = "intake-" + UUID.randomUUID();
    try (AdminClient admin =
        AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
      admin
          .createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
          .all()
          .get(30, TimeUnit.SECONDS);
    }
    return topic;
  }
}
