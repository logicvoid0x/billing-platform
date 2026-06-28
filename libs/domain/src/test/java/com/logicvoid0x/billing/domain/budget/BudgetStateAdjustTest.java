package com.logicvoid0x.billing.domain.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BudgetStateAdjustTest {
  private static final String W = "user_9:5h:2026-11-17T08:00:00Z";

  private static BudgetState state() {
    BudgetState s = new BudgetState(1);
    s.open(W, "org_1", 100_000);
    s.reserve("req_1", 0, Map.of(W, 5_000L));
    s.settle("req_1", 1, Map.of(W, 5_000L));
    return s;
  }

  @Test
  void anAdjustmentMovesTheCounterOnceOnItsKey() {
    BudgetState s = state();
    assertThat(s.adjust(W, 300, "drift:" + W + ":2026-11-17T10:00Z")).isTrue();
    assertThat(s.balance(W).spent()).isEqualTo(5_300);
    assertThat(s.adjust(W, 300, "drift:" + W + ":2026-11-17T10:00Z")).as("replay").isFalse();
    assertThat(s.balance(W).spent()).isEqualTo(5_300);
    assertThat(s.adjust(W, -800, "drift:" + W + ":2026-11-17T11:00Z")).isTrue();
    assertThat(s.balance(W).spent()).isEqualTo(4_500);
  }

  @Test
  void aKeyReusedWithAnotherDeltaIsRejected() {
    BudgetState s = state();
    s.adjust(W, 300, "k1");
    assertThatThrownBy(() -> s.adjust(W, 301, "k1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> s.adjust("other", 1, "k2"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void adjustmentsSurviveASnapshot() throws Exception {
    BudgetState s = state();
    s.adjust(W, 300, "k1");
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(buf)) {
      s.writeTo(out);
    }
    BudgetState r;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(buf.toByteArray()))) {
      r = BudgetState.readFrom(in);
    }
    assertThat(r.adjust(W, 300, "k1")).as("still recognized after restore").isFalse();
    assertThat(r.balance(W).spent()).isEqualTo(5_300);
  }
}
