package com.logicvoid0x.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BudgetAndWindowTest {
  private static final Instant T = Instant.parse("2026-11-17T10:15:30Z");

  @Test
  void budgetIdsFollowTheDesignFormats() {
    assertThat(BudgetIds.balance("org_42")).isEqualTo("org_42:balance");
    assertThat(BudgetIds.spendLimit("ws_7", "2026-11")).isEqualTo("ws_7:limit:2026-11");
    assertThat(BudgetIds.window("user_9", "5h", Instant.parse("2026-11-17T08:00:00Z")))
        .isEqualTo("user_9:5h:2026-11-17T08:00:00Z");
    assertThatThrownBy(() -> BudgetIds.balance("org:42"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void noWindowNeverResets() {
    Window w = Window.none();
    assertThat(w.instanceStart(T, T)).isEqualTo(Instant.EPOCH);
    assertThat(w.resetsAt(T, T)).isEmpty();
  }

  @Test
  void anchoredWindowOpensOnFirstUse() {
    Window w = Window.anchored(Duration.ofHours(5));
    Instant firstUse = Instant.parse("2026-11-17T08:00:00Z");
    assertThat(w.instanceStart(T, firstUse)).isEqualTo(firstUse);
    assertThat(w.resetsAt(T, firstUse)).contains(Instant.parse("2026-11-17T13:00:00Z"));
    // After it expires, the next use opens a new instance.
    Instant later = Instant.parse("2026-11-17T14:00:00Z");
    assertThat(w.instanceStart(later, firstUse)).isEqualTo(later);
  }

  @Test
  void calendarMonthWindowInUtc() {
    Window w = Window.calendarMonth(ZoneOffset.UTC);
    assertThat(w.instanceStart(T, T)).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    assertThat(w.resetsAt(T, T)).contains(Instant.parse("2026-12-01T00:00:00Z"));
    Window d = Window.calendarDay(ZoneOffset.ofHours(-5));
    assertThat(d.instanceStart(T, T)).isEqualTo(Instant.parse("2026-11-17T05:00:00Z"));
    assertThat(d.resetsAt(T, T)).contains(Instant.parse("2026-11-18T05:00:00Z"));
  }

  @Test
  void rollingWindowUsesBuckets() {
    Window w = Window.rolling(Duration.ofDays(7), Duration.ofHours(1));
    assertThat(w.instanceStart(T, T)).isEqualTo(Instant.parse("2026-11-17T10:00:00Z"));
    assertThat(w.resetsAt(T, T)).contains(Instant.parse("2026-11-17T11:00:00Z"));
    assertThatThrownBy(() -> Window.rolling(Duration.ofHours(1), Duration.ofMinutes(7)))
        .as("bucket must divide length")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void windowCodes() {
    assertThat(Window.none().code()).isEqualTo("none");
    assertThat(Window.anchored(Duration.ofHours(5)).code()).isEqualTo("anchored");
    assertThat(Window.calendarMonth(ZoneOffset.UTC).code()).isEqualTo("calendar");
    assertThat(Window.rolling(Duration.ofDays(7), Duration.ofHours(1)).code()).isEqualTo("rolling");
  }

  @Test
  void budgetValidatesUnitAgainstKind() {
    Budget b =
        new Budget(
            "org_42:balance",
            "org_42",
            BudgetKind.BALANCE,
            0L,
            Window.none(),
            FailPolicy.open(2_000),
            1);
    assertThat(b.unit()).isEqualTo(BudgetUnit.CENTS);
    assertThatThrownBy(() ->
            new Budget(
                "ws_7:limit:2026-11",
                "org_42",
                BudgetKind.SPEND_LIMIT,
                -5L,
                Window.calendarMonth(ZoneOffset.UTC),
                FailPolicy.closed(),
                1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
