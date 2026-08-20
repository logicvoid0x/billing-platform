package com.logicvoid0x.billing.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.contracts.v1.GatewayLogEntry;
import com.logicvoid0x.billing.contracts.v1.SettleRecordEvent;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.SettleRecord;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaGatewayEventsTest {
  private static final Instant T = Instant.parse("2026-11-01T10:01:00Z");

  @Test
  void bothRecordsAreKeyedByRequestIdOnTheirTopics() throws Exception {
    MockProducer<String, byte[]> producer =
        new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
    try (KafkaGatewayEvents events = new KafkaGatewayEvents(producer)) {
      events.settled(
          new SettleRecord(
              "req_1",
              "org_acme",
              "us-east",
              T,
              Map.of("org_acme:balance", 1_300L),
              Map.of("org_acme:balance", 1_200L),
              false,
              "rating-1/catalog-v3",
              4));
      events.logged(
          new GatewayEvents.LogEntry(
              "req_1",
              "org_acme",
              "ws_1",
              "key_1",
              "model-x",
              T,
              Map.of(MeterCode.OUTPUT_TOKENS, 1_000_000L),
              1_200,
              "completed"));
    }
    assertThat(producer.history()).hasSize(2);
    var settle = producer.history().get(0);
    assertThat(settle.topic()).isEqualTo("budget.settle");
    assertThat(settle.key()).isEqualTo("req_1");
    SettleRecordEvent s = SettleRecordEvent.parseFrom(settle.value());
    assertThat(s.getSettledByBudgetMap()).containsEntry("org_acme:balance", 1_200L);
    assertThat(s.getSequence()).isEqualTo(4);

    var log = producer.history().get(1);
    assertThat(log.topic()).isEqualTo("gateway.log");
    assertThat(log.key()).isEqualTo("req_1");
    GatewayLogEntry l = GatewayLogEntry.parseFrom(log.value());
    assertThat(l.getQuantitiesMap()).containsEntry("output_tokens", 1_000_000L);
    assertThat(l.getSettledCents()).isEqualTo(1_200);
  }

  @Test
  void deliverySettingsAreExplicit() {
    assertThat(KafkaGatewayEvents.producerConfig("b:9092", "gw"))
        .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
        .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
  }
}
