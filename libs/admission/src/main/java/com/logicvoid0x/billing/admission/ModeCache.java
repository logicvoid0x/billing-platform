package com.logicvoid0x.billing.admission;

import com.logicvoid0x.billing.domain.Mode;
import com.logicvoid0x.billing.domain.budget.ModeSnapshot;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway-side cache of budget modes. Entries are fresh for {@link #TTL_MILLIS} (budget changes are
 * pushed within 5 s); a budget change drops a cached {@code DENY} at once, so a topped-up account
 * is admitted again without waiting for the TTL.
 */
public final class ModeCache {
  /** How long a fetched mode is trusted. */
  public static final long TTL_MILLIS = 5_000;

  private record Entry(Mode mode, long fetchedAtMillis) {}

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  /** Caches every mode of {@code snapshot}, fetched at {@code nowMillis}. */
  public void update(ModeSnapshot snapshot, long nowMillis) {
    snapshot.modeByBudget().forEach((budget, mode) -> put(budget, mode, nowMillis));
  }

  /** Caches one mode. */
  public void put(String budgetId, Mode mode, long nowMillis) {
    entries.put(budgetId, new Entry(mode, nowMillis));
  }

  /** The cached mode, if fresh. */
  public Optional<Mode> mode(String budgetId, long nowMillis) {
    Entry e = entries.get(budgetId);
    if (e == null || nowMillis - e.fetchedAtMillis() >= TTL_MILLIS) {
      return Optional.empty();
    }
    return Optional.of(e.mode());
  }

  /** A budget changed (top-up, new cap): drop a cached {@code DENY} for it. */
  public void onBudgetChange(String budgetId) {
    entries.computeIfPresent(budgetId, (id, e) -> e.mode() == Mode.DENY ? null : e);
  }
}
