package com.logicvoid0x.billing.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.BudgetKind;
import com.logicvoid0x.billing.domain.FailPolicy;
import com.logicvoid0x.billing.domain.Mode;
import com.logicvoid0x.billing.domain.budget.InMemoryBudgetStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

/**
 * Plan Phase 15: load at 100K admissions/s with heavy-tailed orgs, on one gateway host's worth of
 * threads. Org traffic is Zipf-like: a few heavy orgs on warm leases take most requests, while the
 * long tail of small orgs goes DIRECT to the (in-memory) shard.
 */
class AdmissionLoadTest {
  private static final int ORGS = 1_000;
  private static final int THREADS = 8;
  private static final long RUN_NANOS = 2_000_000_000L;

  @Test
  void sustainsOneHundredThousandAdmissionsPerSecondWithHeavyTailedOrgs() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
    InMemoryBudgetStore shard = new InMemoryBudgetStore(1, clock);
    AdmissionController controller =
        new AdmissionController("load", shard, new ModeCache(), new BudgetResolver(), clock);
    List<AdmissionRequest> requests = new ArrayList<>();
    for (int o = 0; o < ORGS; o++) {
      String org = "org_" + o;
      String bal = org + ":balance";
      shard.open(bal, org, 1_000_000_000_000L);
      if (o < 10) { // heavy orgs: warm leases on this host
        controller.forceMode(bal, Mode.LEASE);
        controller.refillLease(bal, 100_000_000_000L);
      }
      requests.add(
          new AdmissionRequest(
              "req_" + o,
              org,
              List.of(new BudgetRef(bal, BudgetKind.BALANCE, FailPolicy.closed())),
              true));
    }
    // Zipf-like pick table: rank r gets weight ~ 1/r.
    int[] picks = new int[100_000];
    double[] cumulative = new double[ORGS];
    double sum = 0;
    for (int r = 0; r < ORGS; r++) {
      sum += 1.0 / (r + 1);
      cumulative[r] = sum;
    }
    for (int i = 0, r = 0; i < picks.length; i++) {
      double u = (i + 0.5) / picks.length * sum;
      while (cumulative[r] < u) {
        r++;
      }
      picks[i] = r;
    }

    LongAdder admitted = new LongAdder();
    LongAdder leased = new LongAdder();
    CountDownLatch start = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    for (int t = 0; t < THREADS; t++) {
      int offset = t * 7_919;
      Thread th =
          new Thread(() -> {
            try {
              start.await();
            } catch (InterruptedException e) {
              return;
            }
            long end = System.nanoTime() + RUN_NANOS;
            int i = offset;
            while (System.nanoTime() < end) {
              AdmissionRequest req = requests.get(picks[i++ % picks.length]);
              Decision d = controller.admit(req, Map.of(req.budgets().get(0).budgetId(), 1L));
              if (d.admitted()) {
                admitted.increment();
                if (d.path() == Decision.Path.LEASE) {
                  leased.increment();
                }
              }
            }
          });
      threads.add(th);
      th.start();
    }
    start.countDown();
    for (Thread th : threads) {
      th.join();
    }

    double perSecond = admitted.sum() / (RUN_NANOS / 1e9);
    System.out.printf(
        "admissions/s: %.0f, on lease: %.1f%%%n", perSecond, 100.0 * leased.sum() / admitted.sum());
    assertThat(perSecond).isGreaterThan(100_000);
    // The ten heavy orgs carry about 39% of a 1/r distribution over 1,000 orgs.
    assertThat((double) leased.sum() / admitted.sum()).isGreaterThan(0.30);
  }
}
