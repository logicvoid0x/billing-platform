package com.logicvoid0x.billing.domain.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.Mode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Modes on the shard state: burn from settled spend, hysteresis, decay, epoch on snapshots. */
class BudgetStateModesTest {
  private static final String B = "org_1:balance";
  private static final long T0 = 1_793_000_000_000L;

  private static Mode mode(BudgetState s) {
    return s.modes("org_1").modeByBudget().get(B);
  }

  /** 100,000 cents capacity; 6,000 cents settled at T0, i.e. a burn of 100 cents/s. */
  private static BudgetState burningAt100PerSecond() {
    BudgetState s = new BudgetState(1);
    s.observeTime(T0);
    s.open(B, "org_1", 100_000);
    s.reserve("req_1", 0, Map.of(B, 6_000L));
    s.settle("req_1", 1, Map.of(B, 6_000L));
    return s;
  }

  @Test
  void anIdleBudgetIsDirect() {
    BudgetState s = new BudgetState(1);
    s.open(B, "org_1", 100_000);
    assertThat(mode(s)).isEqualTo(Mode.DIRECT);
  }

  @Test
  void hysteresisBetweenLeaseAndDirect() {
    BudgetState s = burningAt100PerSecond();
    assertThat(mode(s)).as("94,000 / 100 = 940 s of runway").isEqualTo(Mode.LEASE);

    s.grantLease("host-a", B, 85_000, 0); // available 9,000: 90 s
    assertThat(mode(s)).as("stays LEASE above 60 s").isEqualTo(Mode.LEASE);

    s.grantLease("host-a", B, 3_100, 0); // available 5,900: 59 s
    assertThat(mode(s)).isEqualTo(Mode.DIRECT);

    s.addCapacity(B, 3_100); // back to 90 s
    assertThat(mode(s)).as("re-enters LEASE only above 120 s").isEqualTo(Mode.DIRECT);

    s.addCapacity(B, 3_100); // 12,100: 121 s
    assertThat(mode(s)).isEqualTo(Mode.LEASE);
  }

  @Test
  void denyBelowTheSmallestHoldAndBackWhenToppedUp() {
    BudgetState s = burningAt100PerSecond();
    s.grantLease("host-a", B, 94_000, 0);
    assertThat(mode(s)).isEqualTo(Mode.DENY);
    s.addCapacity(B, 50_000); // 500 s of runway
    assertThat(mode(s)).isEqualTo(Mode.LEASE);
  }

  @Test
  void burnDecaysOnTickSoIdleAccountsDropToDirect() {
    BudgetState s = burningAt100PerSecond();
    assertThat(mode(s)).isEqualTo(Mode.LEASE);
    s.tick(T0 + 30_000);
    assertThat(mode(s)).isEqualTo(Mode.LEASE);
    s.tick(T0 + 70_000);
    assertThat(mode(s)).as("no spend in the last minute").isEqualTo(Mode.DIRECT);
  }

  @Test
  void timeNeverGoesBackwards() {
    BudgetState s = burningAt100PerSecond();
    s.tick(T0 + 70_000);
    s.tick(T0); // an older stamp is ignored
    assertThat(mode(s)).isEqualTo(Mode.DIRECT);
  }

  @Test
  void modesCarryTheEpochAndSurviveASnapshot() throws Exception {
    BudgetState s = burningAt100PerSecond();
    s.advanceEpoch(7);
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
    try (java.io.DataOutputStream out = new java.io.DataOutputStream(buf)) {
      s.writeTo(out);
    }
    BudgetState restored;
    try (java.io.DataInputStream in =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(buf.toByteArray()))) {
      restored = BudgetState.readFrom(in);
    }
    assertThat(restored.modes("org_1").epoch()).isEqualTo(7);
    assertThat(mode(restored)).isEqualTo(Mode.LEASE);
    restored.tick(T0 + 70_000);
    assertThat(mode(restored)).as("burn history was restored too").isEqualTo(Mode.DIRECT);
  }
}
