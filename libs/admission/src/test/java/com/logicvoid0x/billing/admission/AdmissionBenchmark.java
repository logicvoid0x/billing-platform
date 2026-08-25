package com.logicvoid0x.billing.admission;

import com.logicvoid0x.billing.domain.BudgetKind;
import com.logicvoid0x.billing.domain.FailPolicy;
import com.logicvoid0x.billing.domain.Mode;
import com.logicvoid0x.billing.domain.budget.InMemoryBudgetStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * In-process admission on the LEASE path with a warm lease (design: "In-process, about 100 ns").
 * Run by {@link AdmissionBenchmarkTest}.
 */
@State(Scope.Thread)
@BenchmarkMode(org.openjdk.jmh.annotations.Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class AdmissionBenchmark {
  private static final String BAL = "org_bench:balance";

  private AdmissionController controller;
  private AdmissionRequest request;
  private Map<String, Long> holds;

  /** A controller with a warm lease far larger than the run can spend. */
  @Setup
  public void setUp() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
    InMemoryBudgetStore shard = new InMemoryBudgetStore(1, clock);
    shard.open(BAL, "org_bench", 1_000_000_000_000L);
    controller =
        new AdmissionController("bench", shard, new ModeCache(), new BudgetResolver(), clock);
    controller.forceMode(BAL, Mode.LEASE);
    controller.refillLease(BAL, 500_000_000_000L);
    request =
        new AdmissionRequest(
            "req_bench",
            "org_bench",
            List.of(new BudgetRef(BAL, BudgetKind.BALANCE, FailPolicy.closed())),
            true);
    holds = Map.of(BAL, 1L);
  }

  /** One admission from the local lease. */
  @Benchmark
  public Decision admitFromLease() {
    return controller.admit(request, holds);
  }
}
