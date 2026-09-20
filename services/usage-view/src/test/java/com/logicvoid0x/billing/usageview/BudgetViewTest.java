package com.logicvoid0x.billing.usageview;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.BudgetUnit;
import com.logicvoid0x.billing.domain.Window;
import com.logicvoid0x.billing.domain.budget.BudgetBalance;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BudgetViewTest {
  private static final Instant NOW = Instant.parse("2026-11-17T10:30:00Z");

  @Test
  void aFiveHourAllowanceShowsPercentUsedAndWhenItResets() {
    Instant start = Instant.parse("2026-11-17T08:00:00Z");
    BudgetView v =
        BudgetView.of(
            new BudgetBalance("user_9:5h:" + start, "org_1", 200_000, 110_000, 1_500, 500),
            BudgetUnit.USAGE_UNITS,
            Window.anchored(Duration.ofHours(5)),
            start,
            NOW);
    assertThat(v.usedBasisPoints()).isEqualTo(5_600); // 112,000 / 200,000 = 56.00%
    assertThat(v.held()).isEqualTo(2_000);
    assertThat(v.available()).isEqualTo(88_000);
    assertThat(v.resetsAt()).contains(Instant.parse("2026-11-17T13:00:00Z"));
  }

  @Test
  void aMonthlySpendLimitResetsAtTheMonthAndABalanceNever() {
    BudgetView limit =
        BudgetView.of(
            new BudgetBalance("ws_7:limit:2026-11", "org_1", 30_000, 10_001, 0, 0),
            BudgetUnit.CENTS,
            Window.calendarMonth(ZoneOffset.UTC),
            Instant.parse("2026-11-01T00:00:00Z"),
            NOW);
    assertThat(limit.usedBasisPoints()).as("floored, never rounded up").isEqualTo(3_333);
    assertThat(limit.resetsAt()).contains(Instant.parse("2026-12-01T00:00:00Z"));

    BudgetView balance =
        BudgetView.of(
            new BudgetBalance("org_1:balance", "org_1", 5_000, 5_200, 0, 0),
            BudgetUnit.CENTS,
            Window.none(),
            NOW,
            NOW);
    assertThat(balance.available()).as("overdraft shown as negative").isEqualTo(-200);
    assertThat(balance.resetsAt()).isEmpty();
    assertThat(
            BudgetView.of(
                    new BudgetBalance("org_2:balance", "org_2", 0, 0, 0, 0),
                    BudgetUnit.CENTS,
                    Window.none(),
                    NOW,
                    NOW)
                .usedBasisPoints())
        .isEqualTo(10_000);
  }
}
