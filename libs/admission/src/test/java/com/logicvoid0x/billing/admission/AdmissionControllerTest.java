package com.logicvoid0x.billing.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.BudgetKind;
import com.logicvoid0x.billing.domain.FailPolicy;
import com.logicvoid0x.billing.domain.budget.BudgetStore;
import com.logicvoid0x.billing.domain.budget.InMemoryBudgetStore;
import com.logicvoid0x.billing.domain.budget.LeaseGrant;
import com.logicvoid0x.billing.domain.budget.LeaseReport;
import com.logicvoid0x.billing.domain.budget.ModeSnapshot;
import com.logicvoid0x.billing.domain.budget.ReserveResult;
import com.logicvoid0x.billing.domain.budget.SettleResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdmissionControllerTest {
  private static final long T0 = 1_793_000_000_000L;
  private static final String BAL = "org_1:balance";
  private static final String ALW = "user_9:5h:x";
  private static final BudgetRef BALANCE =
      new BudgetRef(BAL, BudgetKind.BALANCE, FailPolicy.open(2_000));
  private static final BudgetRef ALLOWANCE =
      new BudgetRef(ALW, BudgetKind.ALLOWANCE, FailPolicy.open(1_000));

  /** Counts reserve calls and can be switched off to simulate a shard outage. */
  static final class FlakyStore implements BudgetStore {
    final InMemoryBudgetStore inner;
    final AtomicBoolean down = new AtomicBoolean();
    final AtomicInteger reserves = new AtomicInteger();

    FlakyStore(InMemoryBudgetStore inner) {
      this.inner = inner;
    }

    private void check() {
      if (down.get()) {
        throw new IllegalStateException("shard unavailable");
      }
    }

    @Override
    public ReserveResult reserve(String requestId, int sequence, Map<String, Long> holds) {
      check();
      reserves.incrementAndGet();
      return inner.reserve(requestId, sequence, holds);
    }

    @Override
    public ReserveResult extend(String requestId, int sequence, Map<String, Long> holds) {
      check();
      return inner.extend(requestId, sequence, holds);
    }

    @Override
    public SettleResult settle(String requestId, int sequence, Map<String, Long> actual) {
      check();
      return inner.settle(requestId, sequence, actual);
    }

    @Override
    public LeaseGrant grantLease(String hostId, String budgetId, long requested, long epoch) {
      check();
      return inner.grantLease(hostId, budgetId, requested, epoch);
    }

    @Override
    public LeaseReport reportLease(
        String hostId, String budgetId, long leaseId, long spent, long unused) {
      check();
      return inner.reportLease(hostId, budgetId, leaseId, spent, unused);
    }

    @Override
    public ModeSnapshot modes(String orgId) {
      check();
      return inner.modes(orgId);
    }
  }

  private InMemoryBudgetStore shard;
  private FlakyStore store;
  private AdmissionController controller;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(Instant.ofEpochMilli(T0), ZoneOffset.UTC);
    shard = new InMemoryBudgetStore(1, clock);
    shard.open(BAL, "org_1", 10_000);
    shard.open(ALW, "org_1", 500);
    store = new FlakyStore(shard);
    controller =
        new AdmissionController("host-a", store, new ModeCache(), new BudgetResolver(), clock);
  }

  private Decision admit(String id, boolean extraUsage, long cents, long units) {
    return controller.admit(
        new AdmissionRequest(id, "org_1", List.of(BALANCE, ALLOWANCE), extraUsage),
        Map.of(BAL, cents, ALW, units));
  }

  @Test
  void directModeReservesOnTheShard() {
    Decision d = admit("req_1", false, 100, 50);
    assertThat(d.admitted()).isTrue();
    assertThat(d.path()).isEqualTo(Decision.Path.DIRECT);
    assertThat(d.budgets()).containsExactly(ALW);
    assertThat(shard.balance(ALW).held()).isEqualTo(50);
    assertThat(shard.balance(BAL).held()).as("money untouched while allowances admit").isZero();
  }

  @Test
  void exhaustedAllowanceFallsBackToMoneyOnlyWithExtraUsage() {
    assertThat(admit("req_1", false, 100, 600).admitted()).isFalse();
    Decision d = admit("req_2", true, 100, 600);
    assertThat(d.admitted()).isTrue();
    assertThat(d.budgets()).containsExactly(BAL);
    assertThat(shard.balance(BAL).held()).isEqualTo(100);
  }

  @Test
  void leaseModeAdmitsInProcessWithoutCallingTheShard() {
    controller.forceMode(BAL, com.logicvoid0x.billing.domain.Mode.LEASE);
    assertThat(controller.refillLease(BAL, 1_000)).isEqualTo(1_000);
    int before = store.reserves.get();
    Decision d =
        controller.admit(
            new AdmissionRequest("req_1", "org_1", List.of(BALANCE), true), Map.of(BAL, 300L));
    assertThat(d.admitted()).isTrue();
    assertThat(d.path()).isEqualTo(Decision.Path.LEASE);
    assertThat(store.reserves.get()).isEqualTo(before);
    assertThat(controller.lease(BAL).remaining()).isEqualTo(700);
  }

  @Test
  void anEmptyLeaseFallsBackToDirect() {
    controller.forceMode(BAL, com.logicvoid0x.billing.domain.Mode.LEASE);
    controller.refillLease(BAL, 100);
    Decision d =
        controller.admit(
            new AdmissionRequest("req_1", "org_1", List.of(BALANCE), true), Map.of(BAL, 300L));
    assertThat(d.path()).isEqualTo(Decision.Path.DIRECT);
    assertThat(d.admitted()).isTrue();
    assertThat(controller.lease(BAL).remaining()).as("lease untouched").isEqualTo(100);
  }

  @Test
  void denyIsACachedRejectWithNoReserve() {
    controller.forceMode(BAL, com.logicvoid0x.billing.domain.Mode.DENY);
    int before = store.reserves.get();
    Decision d =
        controller.admit(
            new AdmissionRequest("req_1", "org_1", List.of(BALANCE), true), Map.of(BAL, 1L));
    assertThat(d.admitted()).isFalse();
    assertThat(d.reason()).isEqualTo("deny");
    assertThat(store.reserves.get()).isEqualTo(before);
  }

  @Test
  void failOpenAdmitsUpToTheAllowanceWhenTheShardIsDown() {
    store.down.set(true);
    AdmissionRequest req = new AdmissionRequest("r", "org_1", List.of(BALANCE), true);
    long admitted = 0;
    for (int i = 0; i < 30; i++) {
      Decision d =
          controller.admit(
              new AdmissionRequest("req_" + i, "org_1", req.budgets(), true), Map.of(BAL, 150L));
      if (d.admitted()) {
        assertThat(d.path()).isEqualTo(Decision.Path.FAIL_OPEN);
        admitted += 150;
      } else {
        assertThat(d.reason()).isEqualTo("shard_unavailable");
      }
    }
    assertThat(admitted).as("bounded by F_a = 2,000 cents").isEqualTo(1_950);
    assertThat(controller.failOpenUsed(BAL)).isEqualTo(1_950);
  }

  @Test
  void failClosedRefusesWhenTheShardIsDown() {
    store.down.set(true);
    BudgetRef closed = new BudgetRef(BAL, BudgetKind.BALANCE, FailPolicy.closed());
    Decision d =
        controller.admit(
            new AdmissionRequest("req_1", "org_1", List.of(closed), true), Map.of(BAL, 1L));
    assertThat(d.admitted()).isFalse();
    assertThat(d.reason()).isEqualTo("shard_unavailable");
  }

  @Test
  void aMultiBudgetLeaseIsAllOrNothing() {
    BudgetRef limit =
        new BudgetRef("ws_1:limit:2026-11", BudgetKind.SPEND_LIMIT, FailPolicy.closed());
    shard.open("ws_1:limit:2026-11", "org_1", 10_000);
    controller.forceMode(BAL, com.logicvoid0x.billing.domain.Mode.LEASE);
    controller.forceMode("ws_1:limit:2026-11", com.logicvoid0x.billing.domain.Mode.LEASE);
    controller.refillLease(BAL, 1_000);
    controller.refillLease("ws_1:limit:2026-11", 100);
    Decision d =
        controller.admit(
            new AdmissionRequest("req_1", "org_1", List.of(BALANCE, limit), true),
            Map.of(BAL, 300L, "ws_1:limit:2026-11", 300L));
    assertThat(d.path()).isEqualTo(Decision.Path.DIRECT);
    assertThat(controller.lease(BAL).remaining())
        .as("partial lease spend was refunded")
        .isEqualTo(1_000);
  }
}
