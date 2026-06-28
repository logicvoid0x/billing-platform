package com.logicvoid0x.billing.reconciliation.allowance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.domain.budget.InMemoryBudgetStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AllowanceDriftCheckTest {
  private static final Instant HOUR = Instant.parse("2026-11-17T10:00:00Z");
  private static final String W = "user_9:5h:2026-11-17T08:00:00Z";
  private final AllowanceDriftCheck check = AllowanceDriftCheck.standard();

  @Test
  void driftAtOrBelowOnePercentIsLeftAlone() {
    // 1% of 100,000 = 1,000 units.
    assertThat(check.corrections(Map.of(W, 101_000L), Map.of(W, 100_000L), HOUR)).isEmpty();
    assertThat(check.corrections(Map.of(W, 99_000L), Map.of(W, 100_000L), HOUR)).isEmpty();
  }

  @Test
  void driftAboveOnePercentIsCorrectedToMeteredUsage() {
    List<DriftCorrection> c = check.corrections(Map.of(W, 101_001L), Map.of(W, 100_000L), HOUR);
    assertThat(c).containsExactly(new DriftCorrection(W, -1_001, 101_001, 100_000, HOUR));
    assertThat(c.get(0).idempotencyKey()).isEqualTo("allowance_drift:" + W + ":" + HOUR);

    assertThat(check.corrections(Map.of(W, 90_000L), Map.of(W, 100_000L), HOUR))
        .extracting(DriftCorrection::delta)
        .containsExactly(10_000L);
  }

  @Test
  void usageTheShardNeverCountedOrCountedWithoutMeteringIsCorrected() {
    assertThat(check.corrections(Map.of(W, 500L), Map.of(), HOUR))
        .extracting(DriftCorrection::delta)
        .containsExactly(-500L);
    assertThat(check.corrections(Map.of(), Map.of(W, 500L), HOUR))
        .extracting(DriftCorrection::delta)
        .containsExactly(500L);
    assertThat(check.corrections(Map.of(W, 0L), Map.of(W, 0L), HOUR)).isEmpty();
  }

  @Test
  void correctionsAreOrderedByBudget() {
    assertThat(check.corrections(Map.of("b", 0L, "a", 0L), Map.of("b", 10L, "a", 10L), HOUR))
        .extracting(DriftCorrection::budgetId)
        .containsExactly("a", "b");
  }

  @Test
  void thresholdIsValidated() {
    assertThatThrownBy(() -> new AllowanceDriftCheck(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void applyingCorrectionsToTheShardIsIdempotent() {
    InMemoryBudgetStore shard = new InMemoryBudgetStore(1);
    shard.open(W, "org_1", 200_000);
    shard.reserve("req_1", 0, Map.of(W, 110_000L));
    shard.settle("req_1", 1, Map.of(W, 110_000L));
    Map<String, Long> counters = Map.of(W, shard.balance(W).spent());

    List<DriftCorrection> c = check.corrections(counters, Map.of(W, 100_000L), HOUR);
    AllowanceDriftCheck.Adjuster adjuster = shard::adjust;
    assertThat(AllowanceDriftCheck.apply(c, adjuster)).isEqualTo(1);
    assertThat(AllowanceDriftCheck.apply(c, adjuster)).as("re-run of the same hour").isZero();
    assertThat(shard.balance(W).spent()).isEqualTo(100_000);
  }
}
