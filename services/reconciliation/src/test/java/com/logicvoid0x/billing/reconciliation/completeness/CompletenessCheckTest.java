package com.logicvoid0x.billing.reconciliation.completeness;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.reconciliation.completeness.CompletenessCheck.GatewayRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompletenessCheckTest {
  private static final Instant H = Instant.parse("2026-11-01T10:15:00Z");

  private static GatewayRecord record(String id, long output) {
    return new GatewayRecord(
        id,
        "org_1",
        "ws_1",
        "key_1",
        "model-x",
        "standard",
        H,
        Map.of(MeterCode.OUTPUT_TOKENS, output));
  }

  @Test
  void requestsMissingFromMeteringAreReemittedWithTheirOriginalIds() {
    List<GatewayRecord> gateway =
        List.of(
            record("req_ok", 10),
            record("req_lost", 20), // dropped from a host spool
            record("req_dlq", 5),
            record("req_lost", 20), // gateway.log delivered twice
            record("req_refused", 0));
    CompletenessCheck.Result r =
        CompletenessCheck.check(gateway, Set.of("req_ok"), Set.of("req_dlq"));

    assertThat(r.checked()).isEqualTo(3);
    assertThat(r.drift()).isEqualTo(1);
    RequestUsage u = r.reemitted().get(0);
    assertThat(u.requestId()).isEqualTo("req_lost");
    assertThat(u.quantities()).containsEntry(MeterCode.OUTPUT_TOKENS, 20L);
    assertThat(u.eventTime()).isEqualTo(H);
    assertThat(u.hostId()).isEqualTo(CompletenessCheck.REEMIT_HOST);
  }

  @Test
  void afterTheReemitArrivesTheDriftIsZero() {
    List<GatewayRecord> gateway = List.of(record("req_ok", 10), record("req_lost", 20));
    assertThat(CompletenessCheck.check(gateway, Set.of("req_ok", "req_lost"), Set.of()).drift())
        .isZero();
  }
}
