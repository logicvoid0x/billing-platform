package com.logicvoid0x.billing.inferencesim.emitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class UsageEmitterTest {
  static RequestUsage usage(String requestId, String org) {
    return new RequestUsage(
        requestId,
        org,
        "ws_1",
        "key_1",
        "model-x",
        "standard",
        "host-a",
        Instant.parse("2026-11-01T10:15:30.123456789Z"),
        Map.of(MeterCode.INPUT_TOKENS, 1_200L, MeterCode.OUTPUT_TOKENS, 345L));
  }

  @Test
  void deliverySettingsAreExplicit() {
    Map<String, Object> c = UsageEmitter.producerConfig("broker:9092", "usage-emitter-h1");
    assertThat(c)
        .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
        .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        .containsEntry(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE)
        .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000)
        .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
  }

  @Test
  void theCodecRoundTripsEveryFieldAndKeepsMeterCodes() {
    RequestUsage u = usage("req_1", "org_acme");
    assertThat(UsageEventCodec.decode(UsageEventCodec.encode(u))).isEqualTo(u);
    assertThat(UsageEventCodec.toProto(u).getQuantitiesMap())
        .containsOnlyKeys("input_tokens", "output_tokens");
    assertThatThrownBy(() -> UsageEventCodec.decode(new byte[] {1, 2, 3}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theRecordKeyIsTheOrgId() {
    MockProducer<String, byte[]> producer =
        new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
    try (UsageEmitter emitter = new UsageEmitter(producer, "usage.raw")) {
      var unused = emitter.send(usage("req_1", "org_acme"));
    }
    ProducerRecord<String, byte[]> sent = producer.history().get(0);
    assertThat(sent.topic()).isEqualTo("usage.raw");
    assertThat(sent.key()).isEqualTo("org_acme");
    assertThat(UsageEventCodec.decode(sent.value()).requestId()).isEqualTo("req_1");
  }

  @Test
  void aFailedSendCompletesExceptionallyAndIsCounted() {
    MockProducer<String, byte[]> producer =
        new MockProducer<>(false, null, new StringSerializer(), new ByteArraySerializer());
    UsageEmitter emitter = new UsageEmitter(producer, "usage.raw");
    var pending = emitter.send(usage("req_lost", "org_acme"));
    producer.errorNext(new org.apache.kafka.common.errors.TimeoutException("delivery timeout"));

    assertThat(pending).isCompletedExceptionally();
    assertThat(emitter.failed()).isEqualTo(1);
    assertThat(emitter.delivered()).isZero();
  }
}
