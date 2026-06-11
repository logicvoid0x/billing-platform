package com.logicvoid0x.billing.domain.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.domain.Mode;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryBudgetStoreTest {
  private static final String BALANCE = "org_42:balance";
  private static final String LIMIT = "ws_7:limit:2026-11";
  private InMemoryBudgetStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryBudgetStore(1);
    store.advanceEpoch(1);
    store.open(BALANCE, "org_42", 1_000);
    store.open(LIMIT, "org_42", 300);
  }

  @Test
  void reserveHoldsAcrossBudgets() {
    ReserveResult r = store.reserve("req_1", 0, Map.of(BALANCE, 200L, LIMIT, 100L));
    assertThat(r.admitted()).isTrue();
    assertThat(store.balance(BALANCE).held()).isEqualTo(200);
    assertThat(store.balance(BALANCE).available()).isEqualTo(800);
    assertThat(store.balance(LIMIT).available()).isEqualTo(200);
  }

  @Test
  void reserveIsAllOrNothing() {
    ReserveResult r = store.reserve("req_1", 0, Map.of(BALANCE, 200L, LIMIT, 301L));
    assertThat(r.admitted()).isFalse();
    assertThat(r.reason()).isEqualTo(ReserveResult.Reason.INSUFFICIENT);
    assertThat(r.refusedBudgetId()).isEqualTo(LIMIT);
    assertThat(store.balance(BALANCE).held()).isZero();
    assertThat(store.balance(LIMIT).held()).isZero();
  }

  @Test
  void unknownBudgetIsRefused() {
    ReserveResult r = store.reserve("req_1", 0, Map.of("nope", 1L));
    assertThat(r.admitted()).isFalse();
    assertThat(r.reason()).isEqualTo(ReserveResult.Reason.UNKNOWN_BUDGET);
  }

  @Test
  void reserveIsIdempotentOnRequestIdAndSequence() {
    ReserveResult first = store.reserve("req_1", 0, Map.of(BALANCE, 200L));
    ReserveResult replay = store.reserve("req_1", 0, Map.of(BALANCE, 200L));
    assertThat(replay).isEqualTo(first);
    assertThat(store.balance(BALANCE).held()).isEqualTo(200);
    assertThatThrownBy(() -> store.reserve("req_1", 0, Map.of(BALANCE, 999L)))
        .as("same key, different payload")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aRefusedReserveReplaysAsRefusedEvenAfterCreditArrives() {
    ReserveResult refused = store.reserve("req_1", 0, Map.of(BALANCE, 5_000L));
    store.addCapacity(BALANCE, 10_000);
    assertThat(store.reserve("req_1", 0, Map.of(BALANCE, 5_000L))).isEqualTo(refused);
    assertThat(store.reserve("req_1", 1, Map.of(BALANCE, 5_000L)).admitted())
        .as("a new attempt uses a new sequence")
        .isTrue();
  }

  @Test
  void extendAddsHoldsAllOrNothing() {
    store.reserve("req_1", 0, Map.of(BALANCE, 200L, LIMIT, 100L));
    assertThat(store.extend("req_1", 1, Map.of(BALANCE, 100L, LIMIT, 100L)).admitted()).isTrue();
    assertThat(store.balance(BALANCE).held()).isEqualTo(300);
    ReserveResult refused = store.extend("req_1", 2, Map.of(BALANCE, 100L, LIMIT, 101L));
    assertThat(refused.admitted()).isFalse();
    assertThat(store.balance(BALANCE).held()).isEqualTo(300);
    assertThat(store.extend("req_1", 1, Map.of(BALANCE, 100L, LIMIT, 100L)).admitted())
        .as("replayed extend is not applied twice")
        .isTrue();
    assertThat(store.balance(BALANCE).held()).isEqualTo(300);
    assertThat(store.extend("req_unknown", 1, Map.of(BALANCE, 1L)).reason())
        .isEqualTo(ReserveResult.Reason.NO_RESERVATION);
  }

  @Test
  void settleChargesActualAndReleasesTheRest() {
    store.reserve("req_1", 0, Map.of(BALANCE, 200L, LIMIT, 100L));
    SettleResult s = store.settle("req_1", 1, Map.of(BALANCE, 150L, LIMIT, 150L));
    assertThat(s.status()).isEqualTo(SettleResult.Status.SETTLED);
    assertThat(s.released()).containsEntry(BALANCE, 50L).containsEntry(LIMIT, 0L);
    assertThat(store.balance(BALANCE).held()).isZero();
    assertThat(store.balance(BALANCE).spent()).isEqualTo(150);
    // Cost beyond the hold is still charged: bounded overdraft comes only from unheld cost.
    assertThat(store.balance(LIMIT).spent()).isEqualTo(150);
    assertThat(store.balance(LIMIT).available()).isEqualTo(150);
  }

  @Test
  void settleIsIdempotent() {
    store.reserve("req_1", 0, Map.of(BALANCE, 200L));
    SettleResult first = store.settle("req_1", 1, Map.of(BALANCE, 150L));
    assertThat(store.settle("req_1", 1, Map.of(BALANCE, 150L))).isEqualTo(first);
    SettleResult again = store.settle("req_1", 2, Map.of(BALANCE, 150L));
    assertThat(again.status()).isEqualTo(SettleResult.Status.ALREADY_SETTLED);
    assertThat(store.balance(BALANCE).spent()).isEqualTo(150);
    assertThat(store.settle("req_none", 0, Map.of(BALANCE, 1L)).status())
        .isEqualTo(SettleResult.Status.NO_RESERVATION);
  }

  @Test
  void settleRejectsBudgetsOutsideTheReservation() {
    store.reserve("req_1", 0, Map.of(BALANCE, 200L));
    assertThatThrownBy(() -> store.settle("req_1", 1, Map.of(LIMIT, 1L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void amountsMustBeNonNegative() {
    assertThatThrownBy(() -> store.reserve("req_1", 0, Map.of(BALANCE, -1L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.grantLease("host-a", BALANCE, -1, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void leaseGrantsUpToAvailableAndFencesStaleEpochs() {
    LeaseGrant g = store.grantLease("host-a", BALANCE, 300, 1);
    assertThat(g.granted()).isEqualTo(300);
    assertThat(g.epoch()).isEqualTo(1);
    assertThat(store.balance(BALANCE).leased()).isEqualTo(300);
    assertThat(store.grantLease("host-b", BALANCE, 5_000, 1).granted()).isEqualTo(700);
    assertThat(store.balance(BALANCE).available()).isZero();

    store.advanceEpoch(2);
    LeaseGrant stale = store.grantLease("host-a", BALANCE, 10, 1);
    assertThat(stale.granted()).isZero();
    assertThat(stale.staleEpoch()).isTrue();
    assertThat(stale.epoch()).isEqualTo(2);
  }

  @Test
  void modesDenyBelowTheSmallestHold() {
    store.reserve("req_1", 0, Map.of(LIMIT, 300L));
    ModeSnapshot m = store.modes("org_42");
    assertThat(m.modeByBudget())
        .containsEntry(LIMIT, Mode.DENY)
        .containsEntry(BALANCE, Mode.DIRECT);
    assertThat(store.modes("org_other").modeByBudget()).isEmpty();
  }

  @Test
  void concurrentReservesNeverOverspend() throws InterruptedException {
    store.open("org_9:balance", "org_9", 1_000);
    ExecutorService pool = Executors.newFixedThreadPool(16);
    AtomicInteger admitted = new AtomicInteger();
    CountDownLatch done = new CountDownLatch(5_000);
    for (int i = 0; i < 5_000; i++) {
      String id = "req_" + i;
      pool.execute(() -> {
        if (store.reserve(id, 0, Map.of("org_9:balance", 1L)).admitted()) {
          admitted.incrementAndGet();
        }
        done.countDown();
      });
    }
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    pool.shutdown();
    assertThat(admitted.get()).isEqualTo(1_000);
    assertThat(store.balance("org_9:balance").available()).isZero();
  }
}
