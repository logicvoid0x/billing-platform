package com.logicvoid0x.billing.inferencesim.emitter;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.RequestUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** UsageEmitter against a real broker: delivered, decodable, one partition per org. */
@Testcontainers
class UsageEmitterKafkaTest {
  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

  @Test
  void everyEventArrivesKeyedByOrgAndEachOrgStaysOnOnePartition() throws Exception {
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(List.of(new NewTopic(UsageEmitter.TOPIC, 6, (short) 1)))
          .all()
          .get(30, TimeUnit.SECONDS);
    }

    List<RequestUsage> sent = new ArrayList<>();
    Map<String, Integer> partitionOfOrg = new HashMap<>();
    try (UsageEmitter emitter =
        UsageEmitter.create(KAFKA.getBootstrapServers(), "usage-emitter-test", (u, e) -> {})) {
      for (int i = 0; i < 60; i++) {
        RequestUsage u = UsageEmitterTest.usage("req_" + i, "org_" + (i % 5));
        sent.add(u);
        RecordMetadata m = emitter.send(u).get(30, TimeUnit.SECONDS);
        Integer previous = partitionOfOrg.putIfAbsent(u.orgId(), m.partition());
        assertThat(previous == null || previous == m.partition())
            .as("org %s stays on one partition", u.orgId())
            .isTrue();
      }
      assertThat(emitter.delivered()).isEqualTo(60);
      assertThat(emitter.failed()).isZero();
    }

    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "usage-emitter-test");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    List<RequestUsage> received = new ArrayList<>();
    try (KafkaConsumer<String, byte[]> consumer =
        new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
      consumer.subscribe(List.of(UsageEmitter.TOPIC));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (received.size() < sent.size() && System.nanoTime() < deadline) {
        for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
          RequestUsage u = UsageEventCodec.decode(r.value());
          assertThat(r.key()).isEqualTo(u.orgId());
          received.add(u);
        }
      }
    }
    assertThat(received).containsExactlyInAnyOrderElementsOf(sent);
  }
}
