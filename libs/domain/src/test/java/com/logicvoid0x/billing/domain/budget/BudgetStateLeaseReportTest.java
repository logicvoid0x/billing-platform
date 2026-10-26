package com.logicvoid0x.billing.domain.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Lease reporting (Q5): spent moves from leased to spent, unused is released, expiry at the TTL.
 */
class BudgetStateLeaseReportTest {
  private static final String B = "bal_org_1";
  private static final long T0 = 1_000_000;

  private static BudgetState state() {
    BudgetState s = new BudgetState(1);
    s.observeTime(T0);
    s.open(B, "org_1", 10_000);
    return s;
  }

  @Test
  void eachGrantGetsItsOwnLeaseId() {
    BudgetState s = state();
    LeaseGrant first = s.grantLease("gw-1", B, 1_000, 0);
    LeaseGrant second = s.grantLease("gw-1", B, 1_000, 0);
    LeaseGrant otherHost = s.grantLease("gw-2", B, 1_000, 0);
    assertThat(first.leaseId()).isPositive();
    assertThat(second.leaseId()).isGreaterThan(first.leaseId());
    assertThat(otherHost.leaseId()).isPositive();
    assertThat(s.grantLease("gw-1", B, 1_000, -1).leaseId()).as("nothing granted").isZero();
  }

  @Test
  void aReportChargesSpentAndReleasesUnused() {
    BudgetState s = state();
    LeaseGrant g = s.grantLease("gw-1", B, 1_000, 0);
    assertThat(s.balance(B).leased()).isEqualTo(1_000);

    LeaseReport r = s.reportLease("gw-1", B, g.leaseId(), 300, 700);

    assertThat(r.status()).isEqualTo(LeaseReport.Status.REPORTED);
    assertThat(r.released()).isEqualTo(700);
    BudgetBalance b = s.balance(B);
    assertThat(b.leased()).isZero();
    assertThat(b.spent()).isEqualTo(300);
    assertThat(s.rollup("local", T0 + 5_000))
        .extracting(Rollup::amount)
        .as("lease spend reaches the home ledger")
        .containsExactly(300L);
  }

  @Test
  void unusedCreditIsAdmittableAgain() {
    BudgetState s = state();
    LeaseGrant g = s.grantLease("gw-1", B, 10_000, 0);
    assertThat(s.reserve("req_1", 0, Map.of(B, 1L)).admitted()).as("all credit leased").isFalse();

    s.reportLease("gw-1", B, g.leaseId(), 2_000, 8_000);

    assertThat(s.reserve("req_2", 0, Map.of(B, 8_000L)).admitted()).isTrue();
  }

  @Test
  void aReplayedReportIsANoOp() {
    BudgetState s = state();
    LeaseGrant g = s.grantLease("gw-1", B, 1_000, 0);
    LeaseReport first = s.reportLease("gw-1", B, g.leaseId(), 300, 700);
    LeaseReport replay = s.reportLease("gw-1", B, g.leaseId(), 300, 700);

    assertThat(replay).isEqualTo(first);
    assertThat(s.balance(B).spent()).isEqualTo(300);
    assertThat(s.balance(B).leased()).isZero();
  }

  @Test
  void aReplayWithAnotherPayloadIsRejected() {
    BudgetState s = state();
    LeaseGrant g = s.grantLease("gw-1", B, 1_000, 0);
    s.reportLease("gw-1", B, g.leaseId(), 300, 700);
    assertThatThrownBy(() -> s.reportLease("gw-1", B, g.leaseId(), 400, 600))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void spentPlusUnusedMustEqualTheGrant() {
    BudgetState s = state();
    LeaseGrant g = s.grantLease("gw-1", B, 1_000, 0);
    assertThatThrownBy(() -> s.reportLease("gw-1", B, g.leaseId(), 300, 600))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1000");
    assertThat(s.balance(B).leased()).isEqualTo(1_000);
  }

  @Test
  void anUnknownLeaseChangesNothing() {
    BudgetState s = state();
    s.grantLease("gw-1", B, 1_000, 0);
    assertThat(s.reportLease("gw-1", B, 99, 0, 0).status())
        .isEqualTo(LeaseReport.Status.UNKNOWN_LEASE);
    assertThat(s.reportLease("gw-2", B, 1, 0, 1_000).status())
        .as("another host's lease id")
        .isEqualTo(LeaseReport.Status.UNKNOWN_LEASE);
    assertThat(s.balance(B).leased()).isEqualTo(1_000);
  }

  @Test
  void aCrashedHostStrandsCreditForAtMostOneTtl() {
    BudgetState s = state();
    s.grantLease("gw-crashed", B, 4_000, 0);

    s.tick(T0 + BudgetState.LEASE_TTL_MILLIS - 1);
    assertThat(s.balance(B).leased()).as("still live").isEqualTo(4_000);

    s.tick(T0 + BudgetState.LEASE_TTL_MILLIS);
    BudgetBalance b = s.balance(B);
    assertThat(b.leased()).as("no longer stranded as leased").isZero();
    assertThat(b.spent())
        .as("charged at the granted amount, like an auto-settled hold")
        .isEqualTo(4_000);
  }

  @Test
  void aLateReportCorrectsAnExpiredLease() {
    BudgetState s = state();
    LeaseGrant g = s.grantLease("gw-1", B, 4_000, 0);
    s.tick(T0 + BudgetState.LEASE_TTL_MILLIS);

    LeaseReport r = s.reportLease("gw-1", B, g.leaseId(), 1_000, 3_000);

    assertThat(r.status()).isEqualTo(LeaseReport.Status.REPORTED);
    assertThat(r.released()).isEqualTo(3_000);
    assertThat(s.balance(B).spent()).isEqualTo(1_000);
    assertThat(s.rollup("local", T0 + 5_000))
        .extracting(Rollup::amount)
        .as("4000 charged at expiry, -3000 corrected")
        .containsExactly(1_000L);
  }

  @Test
  void expiredLeasesAreForgottenAfterTheMaxHoldTime() {
    BudgetState s = state();
    LeaseGrant g = s.grantLease("gw-1", B, 4_000, 0);
    s.tick(T0 + BudgetState.LEASE_TTL_MILLIS + BudgetState.DEFAULT_MAX_HOLD_MILLIS);
    assertThat(s.reportLease("gw-1", B, g.leaseId(), 0, 4_000).status())
        .as("too late: reconciliation corrects it")
        .isEqualTo(LeaseReport.Status.UNKNOWN_LEASE);
    assertThat(s.balance(B).spent()).isEqualTo(4_000);
  }

  @Test
  void leasesAndReportsSurviveASnapshot() throws Exception {
    BudgetState s = state();
    LeaseGrant reported = s.grantLease("gw-1", B, 1_000, 0);
    s.reportLease("gw-1", B, reported.leaseId(), 300, 700);
    LeaseGrant open = s.grantLease("gw-1", B, 2_000, 0);

    BudgetState r = roundTrip(s);

    assertThat(r.reportLease("gw-1", B, reported.leaseId(), 300, 700).released())
        .as("replay still recognized")
        .isEqualTo(700);
    assertThat(r.balance(B).spent()).isEqualTo(300);
    assertThat(r.reportLease("gw-1", B, open.leaseId(), 2_000, 0).status())
        .isEqualTo(LeaseReport.Status.REPORTED);
    assertThat(r.grantLease("gw-1", B, 1, 0).leaseId())
        .as("ids keep increasing after restore")
        .isGreaterThan(open.leaseId());
    assertThat(bytes(roundTrip(s))).isEqualTo(bytes(s));
  }

  @Test
  void theInMemoryStoreReportsLeases() {
    InMemoryBudgetStore store = new InMemoryBudgetStore(1);
    store.open(B, "org_1", 10_000);
    LeaseGrant g = store.grantLease("gw-1", B, 1_000, 0);
    assertThat(store.reportLease("gw-1", B, g.leaseId(), 100, 900).released()).isEqualTo(900);
    assertThat(store.balance(B).spent()).isEqualTo(100);
    assertThat(List.of(store.balance(B).leased())).containsExactly(0L);
  }

  private static byte[] bytes(BudgetState s) throws Exception {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(buf)) {
      s.writeTo(out);
    }
    return buf.toByteArray();
  }

  private static BudgetState roundTrip(BudgetState s) throws Exception {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes(s)))) {
      return BudgetState.readFrom(in);
    }
  }
}
