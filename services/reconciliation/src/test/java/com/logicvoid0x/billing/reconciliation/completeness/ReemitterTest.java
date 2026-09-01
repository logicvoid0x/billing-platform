package com.logicvoid0x.billing.reconciliation.completeness;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.contracts.codec.UsageEventCodec;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class ReemitterTest {
  @Test
  void missingUsageGoesBackToUsageRawKeyedByOrgWithItsOriginalId() throws Exception {
    MockProducer<String, byte[]> producer =
        new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
    RequestUsage lost =
        new RequestUsage(
            "req_lost",
            "org_1",
            "ws_1",
            "key_1",
            "model-x",
            "standard",
            CompletenessCheck.REEMIT_HOST,
            Instant.parse("2026-11-01T10:15:00Z"),
            Map.of(MeterCode.OUTPUT_TOKENS, 20L));
    assertThat(new Reemitter(producer).reemit(List.of(lost))).isEqualTo(1);
    var sent = producer.history().get(0);
    assertThat(sent.topic()).isEqualTo("usage.raw");
    assertThat(sent.key()).isEqualTo("org_1");
    assertThat(UsageEventCodec.decode(sent.value())).isEqualTo(lost);
  }
}
