package com.logicvoid0x.billing.domain.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rollups to the home ledger and auto-settle of stale holds. */
class BudgetStateMaintenanceTest {
  private static final String B = "org_1:balance";
  private static final String L = "ws_1:limit:2026-11";
  private static final long T0 = 1_793_000_000_000L;
  private static final long MAX_HOLD = 660_000; // 10 min request + 1 min grace

  private static BudgetState state() {
    BudgetState s = new BudgetState(1, MAX_HOLD);
    s.observeTime(T0);
    s.open(B, "org_1", 100_000);
    s.open(L, "org_1", 50_000);
    return s;
  }

  private static void spend(BudgetState s, String req, long amount) {
    s.reserve(req, 0, Map.of(B, amount));
    s.settle(req, 1, Map.of(B, amount));
  }

  @Test
  void rollupDrainsSettledSpendOncePerWindow() {
    BudgetState s = state();
    spend(s, "req_1", 300);
    spend(s, "req_2", 200);
    List<Rollup> first = s.rollup("us-east", T0 + 5_000);
    assertThat(first).containsExactly(new Rollup("org_1", B, "us-east", 500, 0, T0 + 5_000));
    assertThat(first.get(0).idempotencyKey()).isEqualTo("org_1:us-east:" + B + ":" + (T0 + 5_000));

    assertThat(s.rollup("us-east", T0 + 5_000)).as("replay of the same window").isEqualTo(first);
    assertThat(s.rollup("us-east", T0)).as("an older window").isEmpty();

    spend(s, "req_3", 40);
    assertThat(s.rollup("us-east", T0 + 10_000))
        .containsExactly(new Rollup("org_1", B, "us-east", 40, T0 + 5_000, T0 + 10_000));
    assertThat(s.rollup("us-east", T0 + 15_000)).as("idle accounts send nothing").isEmpty();
  }

  @Test
  void rollupsSumToTotalSpend() {
    BudgetState s = state();
    long rolled = 0;
    for (int i = 0; i < 50; i++) {
      spend(s, "req_" + i, 7 + i);
      if (i % 7 == 0) {
        rolled += s.rollup("eu", T0 + 5_000L * (i + 1)).stream().mapToLong(Rollup::amount).sum();
      }
    }
    rolled += s.rollup("eu", T0 + 5_000L * 100).stream().mapToLong(Rollup::amount).sum();
    assertThat(rolled).isEqualTo(s.balance(B).spent());
  }

  @Test
  void staleHoldsAutoSettleAtTheHeldAmount() {
    BudgetState s = state();
    s.reserve("req_lost", 0, Map.of(B, 800L, L, 800L));
    s.tick(T0 + MAX_HOLD - 1);
    assertThat(s.balance(B).held()).as("not stale yet").isEqualTo(800);

    s.tick(T0 + MAX_HOLD);
    assertThat(s.balance(B).held()).isZero();
    assertThat(s.balance(B).spent()).isEqualTo(800);
    assertThat(s.balance(L).spent()).isEqualTo(800);
    assertThat(s.rollup("us-east", T0 + MAX_HOLD + 5_000))
        .extracting(Rollup::budgetId, Rollup::amount)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(B, 800L),
            org.assertj.core.groups.Tuple.tuple(L, 800L));
  }

  @Test
  void aLateSettleCorrectsAnAutoSettledHold() {
    BudgetState s = state();
    s.reserve("req_slow", 0, Map.of(B, 800L));
    s.tick(T0 + MAX_HOLD);
    s.rollup("us-east", T0 + MAX_HOLD + 5_000);

    SettleResult late = s.settle("req_slow", 1, Map.of(B, 300L));
    assertThat(late.status()).isEqualTo(SettleResult.Status.SETTLED);
    assertThat(late.released()).containsEntry(B, 500L);
    assertThat(s.balance(B).spent()).isEqualTo(300);
    assertThat(s.rollup("us-east", T0 + MAX_HOLD + 10_000))
        .as("the correction is rolled up, negative")
        .extracting(Rollup::amount)
        .containsExactly(-500L);
    assertThat(s.settle("req_slow", 2, Map.of(B, 300L)).status())
        .as("only one correction")
        .isEqualTo(SettleResult.Status.ALREADY_SETTLED);
  }

  @Test
  void aLateSettleAboveTheHoldChargesTheDifference() {
    BudgetState s = state();
    s.reserve("req_slow", 0, Map.of(B, 800L));
    s.tick(T0 + MAX_HOLD);
    s.settle("req_slow", 1, Map.of(B, 1_000L));
    assertThat(s.balance(B).spent()).isEqualTo(1_000);
  }

  @Test
  void maintenanceStateSurvivesASnapshot() throws Exception {
    BudgetState s = state();
    s.reserve("req_open", 0, Map.of(B, 100L));
    spend(s, "req_done", 250);
    s.rollup("us-east", T0 + 5_000);
    spend(s, "req_more", 30);

    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(buf)) {
      s.writeTo(out);
    }
    BudgetState r;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(buf.toByteArray()))) {
      r = BudgetState.readFrom(in);
    }
    assertThat(r.rollup("us-east", T0 + 5_000)).as("last window replays").hasSize(1);
    assertThat(r.rollup("us-east", T0 + 10_000)).extracting(Rollup::amount).containsExactly(30L);
    r.tick(T0 + MAX_HOLD);
    assertThat(r.balance(B).held()).as("open time was restored").isZero();
  }
}
