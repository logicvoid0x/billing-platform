package com.logicvoid0x.billing.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.BudgetKind;
import com.logicvoid0x.billing.domain.FailPolicy;
import com.logicvoid0x.billing.domain.Mode;
import com.logicvoid0x.billing.domain.budget.ModeSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModeCacheAndResolverTest {
  private static final long T0 = 1_793_000_000_000L;

  @Test
  void cachedModesAreFreshForFiveSeconds() {
    ModeCache cache = new ModeCache();
    cache.update(new ModeSnapshot("org_1", Map.of("b1", Mode.LEASE, "b2", Mode.DENY), 1), T0);
    assertThat(cache.mode("b1", T0 + 4_999)).contains(Mode.LEASE);
    assertThat(cache.mode("b1", T0 + 5_000)).as("stale").isEmpty();
    assertThat(cache.mode("unknown", T0)).isEmpty();
  }

  @Test
  void aBudgetChangeDropsACachedDenyButKeepsOtherModes() {
    ModeCache cache = new ModeCache();
    cache.update(new ModeSnapshot("org_1", Map.of("b1", Mode.LEASE, "b2", Mode.DENY), 1), T0);
    cache.onBudgetChange("b2");
    cache.onBudgetChange("b1");
    assertThat(cache.mode("b2", T0)).isEmpty();
    assertThat(cache.mode("b1", T0)).contains(Mode.LEASE);
  }

  private static final BudgetRef ALLOWANCE =
      new BudgetRef("user_9:5h:x", BudgetKind.ALLOWANCE, FailPolicy.open(1_000));
  private static final BudgetRef BALANCE =
      new BudgetRef("org_1:balance", BudgetKind.BALANCE, FailPolicy.open(2_000));
  private static final BudgetRef LIMIT =
      new BudgetRef("ws_1:limit:2026-11", BudgetKind.SPEND_LIMIT, FailPolicy.closed());

  @Test
  void allowancesFirstThenMoneyOnlyWithExtraUsage() {
    BudgetResolver resolver = new BudgetResolver();
    List<BudgetRef> budgets = List.of(BALANCE, ALLOWANCE, LIMIT);
    assertThat(resolver.groups(budgets, true))
        .containsExactly(List.of(ALLOWANCE), List.of(BALANCE, LIMIT));
    assertThat(resolver.groups(budgets, false)).containsExactly(List.of(ALLOWANCE));
    assertThat(resolver.groups(List.of(BALANCE, LIMIT), false))
        .as("a pure pay-as-you-go org uses its money budgets")
        .containsExactly(List.of(BALANCE, LIMIT));
  }
}
