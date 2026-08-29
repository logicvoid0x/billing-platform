package com.logicvoid0x.billing.gateway;

import com.google.protobuf.InvalidProtocolBufferException;
import com.logicvoid0x.billing.admission.AdmissionController;
import com.logicvoid0x.billing.contracts.v1.BudgetChange;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies {@code budget.changes} on a gateway host: each change drops the cached mode of its
 * budget, so a topped-up account at $0 is admitted again on its next request instead of after the 5
 * s mode TTL. Changes are idempotent on (budget_id, version); an older or repeated version is
 * ignored.
 */
public final class BudgetChangeListener {
  private final AdmissionController admission;
  private final Map<String, Long> applied = new ConcurrentHashMap<>();

  /** Creates the listener for this host's admission controller. */
  public BudgetChangeListener(AdmissionController admission) {
    this.admission = Objects.requireNonNull(admission, "admission");
  }

  /**
   * Applies one record value from budget.changes.
   *
   * @return true if the change was new and applied
   */
  public boolean apply(byte[] value) {
    BudgetChange change;
    try {
      change = BudgetChange.parseFrom(value);
    } catch (InvalidProtocolBufferException e) {
      return false; // not ours to fail on; reconciliation and the TTL cover a lost change
    }
    boolean[] fresh = new boolean[1];
    applied.compute(
        change.getBudgetId(),
        (id, last) -> {
          fresh[0] = last == null || change.getVersion() > last;
          return fresh[0] ? change.getVersion() : last;
        });
    if (fresh[0]) {
      admission.onBudgetChange(change.getBudgetId());
    }
    return fresh[0];
  }
}
