package com.logicvoid0x.billing.reconciliation.completeness;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.v1.GatewayLogEntry;
import com.logicvoid0x.billing.domain.MeterCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayLogReaderTest {
  private static final Instant H10 = Instant.parse("2026-11-01T10:00:00Z");

  private static byte[] entry(String id, Instant at, long output) {
    return GatewayLogEntry.newBuilder()
        .setRequestId(id)
        .setOrgId("org_1")
        .setWorkspaceId("ws_1")
        .setApiKeyId("key_1")
        .setModel("model-x")
        .setEventTime(Timestamp.newBuilder().setSeconds(at.getEpochSecond()))
        .putQuantities("output_tokens", output)
        .setOutcome("completed")
        .build()
        .toByteArray();
  }

  @Test
  void keepsOnlyTheHoursRecordsAndDecodesMeterCodes() {
    List<byte[]> values =
        List.of(
            entry("req_a", H10.plusSeconds(5), 10),
            entry("req_b", H10.plusSeconds(3_599), 20),
            entry("req_next", H10.plusSeconds(3_600), 30),
            entry("req_prev", H10.minusSeconds(1), 40),
            new byte[] {(byte) 0xff, 0x01});
    var records = GatewayLogReader.forHour(values, H10.plusSeconds(1_200));
    assertThat(records)
        .extracting(CompletenessCheck.GatewayRecord::requestId)
        .containsExactly("req_a", "req_b");
    assertThat(records.get(1).quantities()).containsEntry(MeterCode.OUTPUT_TOKENS, 20L);
  }
}
