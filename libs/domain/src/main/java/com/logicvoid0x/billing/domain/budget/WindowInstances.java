package com.logicvoid0x.billing.domain.budget;

import com.logicvoid0x.billing.domain.BudgetIds;
import com.logicvoid0x.billing.domain.Window;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Budget ID of the window instance in effect at a time. Anchored and calendar windows get a new
 * budget ID per instance (so a reset is a fresh budget, never an edit); a rolling window and a
 * budget with no window keep one ID, and usage ages out of a rolling window bucket by bucket.
 */
public final class WindowInstances {
  private WindowInstances() {}

  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuu-MM-dd");

  /**
   * The budget ID for {@code ownerId}'s {@code windowName} budget at {@code at}.
   *
   * @param ownerId org, workspace, API key or user
   * @param windowName e.g. {@code limit}, {@code 5h}, {@code 7d}
   * @param window how the budget resets
   * @param at time of use
   * @param currentStart start of the current anchored instance (ignored by other windows)
   */
  public static String budgetId(
      String ownerId, String windowName, Window window, Instant at, Instant currentStart) {
    if (window instanceof Window.Calendar c) {
      ZonedDateTime start = window.instanceStart(at, currentStart).atZone(c.zone());
      String label =
          c.period() == Window.Calendar.Period.MONTH ? MONTH.format(start) : DAY.format(start);
      return "limit".equals(windowName)
          ? BudgetIds.spendLimit(ownerId, label)
          : ownerId + ":" + windowName + ":" + label;
    }
    if (window instanceof Window.Anchored) {
      return BudgetIds.window(ownerId, windowName, window.instanceStart(at, currentStart));
    }
    return ownerId + ":" + windowName;
  }
}
