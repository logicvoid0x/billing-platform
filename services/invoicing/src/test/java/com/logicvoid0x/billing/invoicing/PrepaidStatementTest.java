package com.logicvoid0x.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrepaidStatementTest {
  private static final Instant NOV = Instant.parse("2026-11-01T00:00:00Z");

  @Test
  void aStatementBalancesAndEqualsTheRatedLedger() {
    PrepaidStatement s =
        new PrepaidStatement(
            "org_pre",
            NOV,
            Map.of("sku_out", 48_000L, "sku_in", 2_000L),
            10_000,
            60_000,
            50_000,
            500,
            19_500);
    assertThat(s.requireConsistent().ratedTotal()).isEqualTo(50_000);
    assertThat(s.lines().keySet()).containsExactly("sku_in", "sku_out");
  }

  @Test
  void aPendingTrueUpOrAMissingMovementBlocksTheStatement() {
    assertThatThrownBy(() ->
            new PrepaidStatement(
                    "org_pre", NOV, Map.of("sku_out", 48_000L), 0, 50_000, 47_950, 0, 2_050)
                .requireConsistent())
        .hasMessageContaining("true-up pending");
    assertThatThrownBy(() ->
            new PrepaidStatement(
                    "org_pre", NOV, Map.of("sku_out", 48_000L), 0, 50_000, 48_000, 0, 2_100)
                .requireConsistent())
        .hasMessageContaining("closing 2100 != 2000");
  }
}
