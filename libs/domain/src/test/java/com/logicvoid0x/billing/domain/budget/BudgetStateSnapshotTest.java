package com.logicvoid0x.billing.domain.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BudgetStateSnapshotTest {
  private static byte[] bytes(BudgetState s) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(buf)) {
      s.writeTo(out);
    }
    return buf.toByteArray();
  }

  private static BudgetState restore(byte[] b) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(b))) {
      return BudgetState.readFrom(in);
    }
  }

  private static BudgetState populated() {
    BudgetState s = new BudgetState(5);
    s.advanceEpoch(3);
    s.open("org_1:balance", "org_1", 1_000);
    s.open("ws_1:limit:2026-11", "org_1", 400);
    s.reserve("req_a", 0, Map.of("org_1:balance", 100L, "ws_1:limit:2026-11", 100L));
    s.reserve("req_b", 0, Map.of("org_1:balance", 5_000L)); // refused, still recorded
    s.extend("req_a", 1, Map.of("org_1:balance", 50L));
    s.reserve("req_c", 0, Map.of("org_1:balance", 10L));
    s.settle("req_c", 1, Map.of("org_1:balance", 7L));
    s.grantLease("host-a", "org_1:balance", 200, 3);
    return s;
  }

  @Test
  void restoredStateWritesTheSameBytes() throws IOException {
    byte[] original = bytes(populated());
    assertThat(bytes(restore(original))).isEqualTo(original);
    assertThat(bytes(populated())).as("deterministic").isEqualTo(original);
  }

  @Test
  void restoredStateKeepsBalancesAndRecognizesReplays() throws IOException {
    BudgetState s = restore(bytes(populated()));
    BudgetBalance b = s.balance("org_1:balance");
    assertThat(b.held()).isEqualTo(150);
    assertThat(b.spent()).isEqualTo(7);
    assertThat(b.leased()).isEqualTo(200);
    assertThat(s.epoch()).isEqualTo(3);

    // Replays return the recorded results and change nothing.
    assertThat(s.reserve("req_b", 0, Map.of("org_1:balance", 5_000L)).admitted()).isFalse();
    assertThat(s.settle("req_c", 1, Map.of("org_1:balance", 7L)).status())
        .isEqualTo(SettleResult.Status.SETTLED);
    assertThat(s.balance("org_1:balance").spent()).isEqualTo(7);

    // And the state keeps working: settle req_a, which releases the rest of its holds.
    SettleResult settled =
        s.settle("req_a", 2, Map.of("org_1:balance", 120L, "ws_1:limit:2026-11", 90L));
    assertThat(settled.released())
        .containsEntry("org_1:balance", 30L)
        .containsEntry("ws_1:limit:2026-11", 10L);
    assertThat(s.balance("org_1:balance").available()).isEqualTo(1_000 - 127 - 200);
  }
}
