package com.logicvoid0x.billing.domain.budget;

import com.logicvoid0x.billing.domain.Mode;
import java.util.Map;

/**
 * Modes of an org's budgets at one point.
 *
 * @param orgId the org
 * @param modeByBudget budgetId to mode
 * @param epoch the shard's epoch
 */
public record ModeSnapshot(String orgId, Map<String, Mode> modeByBudget, long epoch) {
  /** Copies the map. */
  public ModeSnapshot {
    modeByBudget = Map.copyOf(modeByBudget);
  }
}
