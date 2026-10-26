package com.logicvoid0x.billing.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.admission.AdmissionControllerTest.FlakyStore;
import com.logicvoid0x.billing.domain.BudgetKind;
import com.logicvoid0x.billing.domain.FailPolicy;
import com.logicvoid0x.billing.domain.Mode;
import com.logicvoid0x.billing.domain.budget.BudgetBalance;
import com.logicvoid0x.billing.domain.budget.InMemoryBudgetStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The gateway reports what it spent from each lease and returns the rest (Q5). */
class LeaseReportingTest {
  private static final long T0 = 1_000_000;
  private static final String BAL = "org_1:balance";
  private static final BudgetRef BALANCE =
      new BudgetRef(BAL, BudgetKind.BALANCE, FailPolicy.open(2_000));

  private final AtomicLong now = new AtomicLong(T0);
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return Instant.ofEpochMilli(now.get());
        }
      };

  private InMemoryBudgetStore shard;
  private FlakyStore store;
  private AdmissionController controller;

  @BeforeEach
  void setUp() {
    shard = new InMemoryBudgetStore(1, clock);
    shard.open(BAL, "org_1", 10_000);
    store = new FlakyStore(shard);
    controller =
        new AdmissionController("gw-1", store, new ModeCache(), new BudgetResolver(), clock);
    controller.forceMode(BAL, Mode.LEASE);
  }

  private boolean spend(String requestId, long cents) {
    return controller
        .admit(new AdmissionRequest(requestId, "org_1", List.of(BALANCE), true), Map.of(BAL, cents))
        .admitted();
  }

  @Test
  void aReplacedLeaseIsReportedSoItsUnusedCreditIsFreed() {
    controller.refillLease(BAL, 4_000);
    assertThat(spend("req_1", 1_000)).isTrue();

    controller.refillLease(BAL, 2_000);

    BudgetBalance b = shard.balance(BAL);
    assertThat(b.spent()).as("spent from the first lease").isEqualTo(1_000);
    assertThat(b.leased()).as("only the new lease").isEqualTo(2_000);
    assertThat(b.capacity() - b.spent() - b.held() - b.leased()).isEqualTo(7_000);
  }

  @Test
  void shutdownReturnsEveryLease() {
    controller.refillLease(BAL, 4_000);
    assertThat(spend("req_1", 1_500)).isTrue();

    controller.returnLeases();

    BudgetBalance b = shard.balance(BAL);
    assertThat(b.leased()).isZero();
    assertThat(b.spent()).isEqualTo(1_500);
    assertThat(controller.lease(BAL).remaining()).isZero();
    Decision d =
        controller.admit(
            new AdmissionRequest("req_2", "org_1", List.of(BALANCE), true), Map.of(BAL, 1L));
    assertThat(d.path()).as("nothing left to spend locally").isNotEqualTo(Decision.Path.LEASE);
  }

  @Test
  void anExpiredLeaseIsReportedLateAndCorrected() {
    controller.refillLease(BAL, 4_000);
    assertThat(spend("req_1", 500)).isTrue();
    now.addAndGet(AdmissionController.LEASE_TTL_MILLIS);
    shard.tick(); // the shard charges the unreported lease in full

    assertThat(shard.balance(BAL).spent()).isEqualTo(4_000);
    controller.refillLease(BAL, 1_000);

    assertThat(shard.balance(BAL).spent())
        .as("late report corrects to the real spend")
        .isEqualTo(500);
  }

  @Test
  void aShardOutageWhileReportingDoesNotFailTheRefill() {
    controller.refillLease(BAL, 4_000);
    store.down.set(true);
    controller.returnLeases(); // must not throw; the shard expires the lease at its TTL
    store.down.set(false);

    now.addAndGet(AdmissionController.LEASE_TTL_MILLIS);
    shard.tick();
    assertThat(shard.balance(BAL).leased()).as("stranded for at most one TTL").isZero();
    assertThat(controller.unreportedLeases()).isEqualTo(1);
  }
}
