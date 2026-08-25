package com.logicvoid0x.billing.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.admission.AdmissionController;
import com.logicvoid0x.billing.admission.BudgetRef;
import com.logicvoid0x.billing.admission.BudgetResolver;
import com.logicvoid0x.billing.admission.ModeCache;
import com.logicvoid0x.billing.domain.BudgetKind;
import com.logicvoid0x.billing.domain.FailPolicy;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.Mode;
import com.logicvoid0x.billing.domain.budget.InMemoryBudgetStore;
import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Phase 10 "Done when": concurrent streams drain a $3 balance to DENY with overdraft under {@code
 * O_max(a) = C_a * delta + F_a + R_a}, and every stopped stream ends at its funded boundary.
 */
class DrainToDenyTest {
  private static final String ORG = "org_drain";
  private static final String BAL = ORG + ":balance";
  private static final long CHUNK = 2_048;
  private static final int CONCURRENCY = 8;
  private static final RatingLibrary GOLDEN =
      new CatalogRatingLibrary(GoldenCases.catalog(), "drain-test");

  @Test
  void aThreeDollarBalanceDrainsToDenyWithinTheOverdraftBound() throws Exception {
    Clock clock = Clock.fixed(GoldenCases.HOUR.plusSeconds(60), ZoneOffset.UTC);
    // The smallest possible hold here is one chunk (no prompt), so DENY starts below it.
    long smallestHold = GOLDEN.chargeCents(ORG, "sku_out", 0, CHUNK, GoldenCases.HOUR) + 1;
    InMemoryBudgetStore shard = new InMemoryBudgetStore(smallestHold, clock);
    shard.open(BAL, ORG, 300);
    GatewayTest.RecordingEvents events = new GatewayTest.RecordingEvents();
    Gateway gateway =
        new Gateway(
            "gw-1",
            new AdmissionController("gw-1", shard, new ModeCache(), new BudgetResolver(), clock),
            shard,
            t -> GOLDEN,
            new GatewayTest.FakeUpstream(),
            new SynchronizedEvents(events),
            new MinuteLimiter(1_000_000, clock),
            clock,
            CHUNK);
    BudgetRef balance = new BudgetRef(BAL, BudgetKind.BALANCE, FailPolicy.closed());
    AtomicInteger refused = new AtomicInteger();
    ConcurrentLinkedQueue<Gateway.Response> responses = new ConcurrentLinkedQueue<>();

    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
    try {
      List<Future<?>> workers = new ArrayList<>();
      for (int w = 0; w < CONCURRENCY; w++) {
        workers.add(
            pool.submit(() -> {
              while (refused.get() < 3 * CONCURRENCY) {
                Gateway.Response r =
                    gateway.handle(
                        new GatewayRequest(
                            ORG,
                            "ws_1",
                            "key_1",
                            "model-x",
                            "standard",
                            0,
                            0,
                            0,
                            20_000,
                            List.of(balance),
                            true,
                            "us-east",
                            1),
                        text -> {});
                if (r.outcome().equals(Gateway.REFUSED)) {
                  refused.incrementAndGet();
                } else {
                  responses.add(r);
                }
              }
            }));
      }
      for (Future<?> f : workers) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }

    // delta: the most one request can incur beyond its holds, one chunk at $15/M (+1 cent of
    // drift).
    long delta = GOLDEN.chargeCents(ORG, "sku_out", 0, CHUNK, GoldenCases.HOUR) + 1;
    long oMax = CONCURRENCY * delta; // F_a = 0 (fail-closed); R_a is inside the +1 above
    long spent = shard.balance(BAL).spent();
    assertThat(spent).as("the balance was drained").isGreaterThanOrEqualTo(300 - delta);
    assertThat(spent - 300).as("overdraft within O_max = %d cents", oMax).isLessThanOrEqualTo(oMax);
    assertThat(shard.balance(BAL).held()).as("no hold left behind").isZero();

    shard.tick();
    assertThat(shard.modes(ORG).modeByBudget()).containsEntry(BAL, Mode.DENY);

    // Every stream that was stopped ended at a funded-chunk boundary (plus at most one upstream
    // piece of 512 tokens already in flight), never before it.
    for (Gateway.Response r : responses) {
      long delivered = r.delivered().getOrDefault(MeterCode.OUTPUT_TOKENS, 0L);
      if (r.outcome().equals("budget_exhausted")) {
        assertThat(delivered % CHUNK)
            .as("stopped at a funded-chunk boundary (+ one 512-token piece): %d tokens", delivered)
            .isLessThanOrEqualTo(512);
        assertThat(delivered).isGreaterThanOrEqualTo(Math.min(CHUNK, 20_000));
      }
    }
    assertThat(responses).anyMatch(r -> r.outcome().equals("budget_exhausted"));
    assertThat(events.settles).hasSameSizeAs(responses);
  }

  /** The recording events from GatewayTest, made safe for concurrent requests. */
  private record SynchronizedEvents(GatewayTest.RecordingEvents inner) implements GatewayEvents {
    @Override
    public synchronized void settled(com.logicvoid0x.billing.domain.SettleRecord record) {
      synchronized (inner) {
        inner.settled(record);
      }
    }

    @Override
    public void logged(LogEntry entry) {
      synchronized (inner) {
        inner.logged(entry);
      }
    }
  }
}
