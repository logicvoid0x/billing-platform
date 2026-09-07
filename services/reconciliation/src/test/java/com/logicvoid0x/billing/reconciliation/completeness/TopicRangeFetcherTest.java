package com.logicvoid0x.billing.reconciliation.completeness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
class TopicRangeFetcherTest {
  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

  private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

  @Test
  void returnsExactlyTheRecordsTimestampedInTheWindowAcrossPartitions() throws Exception {
    String topic = "gateway.log";
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(List.of(new NewTopic(topic, 3, (short) 1)))
          .all()
          .get(30, TimeUnit.SECONDS);
    }
    try (KafkaProducer<byte[], byte[]> p =
        new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
            new ByteArraySerializer(),
            new ByteArraySerializer())) {
      for (int i = 0; i < 30; i++) {
        long ts = T0.plusSeconds(600L * i).toEpochMilli(); // every 10 min, 5 h in all
        p.send(
                new ProducerRecord<>(
                    topic, i % 3, ts, null, ("r" + i).getBytes(StandardCharsets.UTF_8)))
            .get();
      }
    }
    try (KafkaConsumer<byte[], byte[]> c =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false),
            new ByteArrayDeserializer(),
            new ByteArrayDeserializer())) {
      List<String> got =
          new TopicRangeFetcher(c, Duration.ofMillis(500))
              .fetch(topic, T0.plusSeconds(3_600), T0.plusSeconds(7_200)).stream()
                  .map(b -> new String(b, StandardCharsets.UTF_8))
                  .toList();
      // Hour 11:00-12:00 holds records 6..11.
      assertThat(got).containsExactlyInAnyOrder("r6", "r7", "r8", "r9", "r10", "r11");
      assertThat(
              new TopicRangeFetcher(c, Duration.ofMillis(500))
                  .fetch(topic, T0.plusSeconds(100 * 3_600), T0.plusSeconds(101 * 3_600)))
          .as("a window after the last record")
          .isEmpty();
    }
  }
}
