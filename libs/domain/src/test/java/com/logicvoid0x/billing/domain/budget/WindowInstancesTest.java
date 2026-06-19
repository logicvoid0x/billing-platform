package com.logicvoid0x.billing.domain.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.Window;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WindowInstancesTest {
  private static final Instant T = Instant.parse("2026-11-17T10:15:30Z");

  @Test
  void calendarMonthLimitUsesThePeriodLabel() {
    assertThat(
            WindowInstances.budgetId("ws_7", "limit", Window.calendarMonth(ZoneOffset.UTC), T, T))
        .isEqualTo("ws_7:limit:2026-11");
    assertThat(
            WindowInstances.budgetId(
                "ws_7",
                "limit",
                Window.calendarMonth(ZoneOffset.UTC),
                Instant.parse("2026-12-01T00:00:00Z"),
                T))
        .as("a new month is a new budget instance")
        .isEqualTo("ws_7:limit:2026-12");
    assertThat(
            WindowInstances.budgetId(
                "key_3", "daily", Window.calendarDay(ZoneOffset.ofHours(-5)), T, T))
        .isEqualTo("key_3:daily:2026-11-17");
  }

  @Test
  void anchoredWindowIsNamedByItsStart() {
    Instant firstUse = Instant.parse("2026-11-17T08:00:00Z");
    Window fiveHours = Window.anchored(Duration.ofHours(5));
    assertThat(WindowInstances.budgetId("user_9", "5h", fiveHours, T, firstUse))
        .isEqualTo("user_9:5h:2026-11-17T08:00:00Z");
    assertThat(
            WindowInstances.budgetId(
                "user_9", "5h", fiveHours, Instant.parse("2026-11-17T14:00:00Z"), firstUse))
        .isEqualTo("user_9:5h:2026-11-17T14:00:00Z");
  }

  @Test
  void rollingAndNoneWindowsKeepOneBudget() {
    assertThat(
            WindowInstances.budgetId(
                "user_9", "7d", Window.rolling(Duration.ofDays(7), Duration.ofHours(1)), T, T))
        .isEqualTo("user_9:7d");
    assertThat(WindowInstances.budgetId("org_42", "balance", Window.none(), T, T))
        .isEqualTo("org_42:balance");
  }
}
