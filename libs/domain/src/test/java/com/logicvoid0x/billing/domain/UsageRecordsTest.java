package com.logicvoid0x.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UsageRecordsTest {
  private static final Instant T = Instant.parse("2026-11-01T10:15:30Z");

  private static RequestUsage usage(Map<MeterCode, Long> quantities) {
    return new RequestUsage(
        "req_1", "org_42", "ws_7", "key_3", "model-x", "standard", "host-a", T, quantities);
  }

  @Test
  void dedupeKeyIsRequestIdAndMeterCode() {
    RequestUsage u = usage(Map.of(MeterCode.OUTPUT_TOKENS, 10L));
    assertThat(u.dedupeKey(MeterCode.OUTPUT_TOKENS)).isEqualTo("req_1:output_tokens");
  }

  @Test
  void eventHourTruncatesToTheHour() {
    assertThat(usage(Map.of()).eventHour()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
  }

  @Test
  void nonZeroMetersAreSortedByCode() {
    Map<MeterCode, Long> q = new HashMap<>();
    q.put(MeterCode.OUTPUT_TOKENS, 5L);
    q.put(MeterCode.INPUT_TOKENS, 7L);
    q.put(MeterCode.CACHE_READ_TOKENS, 0L);
    assertThat(usage(q).nonZeroMeters())
        .containsExactly(MeterCode.INPUT_TOKENS, MeterCode.OUTPUT_TOKENS);
  }

  @Test
  void quantitiesAreCopiedAndImmutable() {
    Map<MeterCode, Long> q = new HashMap<>();
    q.put(MeterCode.OUTPUT_TOKENS, 5L);
    RequestUsage u = usage(q);
    q.put(MeterCode.INPUT_TOKENS, 1L);
    assertThat(u.quantities()).containsOnlyKeys(MeterCode.OUTPUT_TOKENS);
    assertThatThrownBy(() -> u.quantities().put(MeterCode.INPUT_TOKENS, 1L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void requestUsageIsValidated() {
    assertThatThrownBy(() -> usage(Map.of(MeterCode.OUTPUT_TOKENS, -1L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RequestUsage(" ", "org", "ws", "key", "m", "standard", "h", T, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void meteredLineCarriesItsDedupeKeyAndHour() {
    MeteredLine line =
        new MeteredLine(
            "req_1",
            "org_42",
            "ws_1",
            "key_1",
            "sku_out_x",
            MeterCode.OUTPUT_TOKENS,
            40_000_000L,
            T);
    assertThat(line.dedupeKey()).isEqualTo("req_1:output_tokens");
    assertThat(line.eventHour()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
    assertThatThrownBy(() ->
            new MeteredLine("req_1", "org", "ws", "key", "sku", MeterCode.OUTPUT_TOKENS, 0L, T))
        .as("one metered line per non-zero meter")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void settleRecordKeysAndAmountsInCents() {
    SettleRecord s =
        new SettleRecord(
            "req_1",
            "org_42",
            "us-east",
            T,
            Map.of("org_42:balance", 300L),
            Map.of("org_42:balance", 150L),
            false,
            "rating-7",
            2);
    assertThat(s.idempotencyKey()).isEqualTo("req_1:2");
    assertThat(s.eventHour()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
    assertThat(s.settledByBudget()).containsEntry("org_42:balance", 150L);
    assertThat(List.copyOf(s.heldByBudget().values())).containsExactly(300L);
    assertThatThrownBy(() ->
            new SettleRecord("req_1", "org", "r", T, Map.of("b", -1L), Map.of(), false, "v", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SettleRecord("req_1", "org", "r", T, Map.of(), Map.of(), false, "v", -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
