package com.logicvoid0x.billing.admission;

import com.logicvoid0x.billing.domain.Mode;
import com.logicvoid0x.billing.domain.budget.BudgetState;
import com.logicvoid0x.billing.domain.budget.BudgetStore;
import com.logicvoid0x.billing.domain.budget.LeaseGrant;
import com.logicvoid0x.billing.domain.budget.ReserveResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The gateway's admission path. Budget groups are tried in order (allowances, then money); a group
 * admits only if every budget in it does:
 *
 * <ul>
 *   <li>any budget in {@code DENY}: the group refuses, with no shard call;
 *   <li>every budget in {@code LEASE}: spend the local leases (all or nothing, in-process);
 *   <li>otherwise: one all-or-nothing reserve on the regional shard ({@code DIRECT});
 *   <li>shard unreachable: each budget's fail policy, open up to {@code F_a} or closed.
 * </ul>
 *
 * The direct reserve uses the group's index as its sequence, so a retried admission of the same
 * request is idempotent on the shard.
 */
public final class AdmissionController {
  /** Lease time-to-live (about 2 s of host burn). */
  public static final long LEASE_TTL_MILLIS = BudgetState.LEASE_TTL_MILLIS;

  private final String hostId;
  private final BudgetStore store;
  private final ModeCache modes;
  private final BudgetResolver resolver;
  private final Clock clock;
  private final Map<String, LocalLease> leases = new ConcurrentHashMap<>();
  private final Map<String, AtomicLong> failOpenUsed = new ConcurrentHashMap<>();
  private final AtomicLong unreportedLeases = new AtomicLong();

  /** Creates the controller for one gateway host. */
  public AdmissionController(
      String hostId, BudgetStore store, ModeCache modes, BudgetResolver resolver, Clock clock) {
    this.hostId = Objects.requireNonNull(hostId, "hostId");
    this.store = Objects.requireNonNull(store, "store");
    this.modes = Objects.requireNonNull(modes, "modes");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Admits {@code request} against {@code holds} (budgetId to amount in that budget's unit: cents
   * from {@code Cents.hold}, usage units from the weight book).
   */
  public Decision admit(AdmissionRequest request, Map<String, Long> holds) {
    List<List<BudgetRef>> groups = resolver.groups(request.budgets(), request.extraUsage());
    if (groups.isEmpty()) {
      return Decision.refused("no_budgets");
    }
    Decision last = Decision.refused("no_budgets");
    for (int i = 0; i < groups.size(); i++) {
      last = admitGroup(request, groups.get(i), holds, i);
      if (last.admitted()) {
        return last;
      }
    }
    return last;
  }

  private Decision admitGroup(
      AdmissionRequest request, List<BudgetRef> group, Map<String, Long> holds, int sequence) {
    long now = clock.millis();
    Map<String, Long> groupHolds = new LinkedHashMap<>();
    List<String> ids = new ArrayList<>();
    for (BudgetRef b : group) {
      groupHolds.put(b.budgetId(), holds.getOrDefault(b.budgetId(), 0L));
      ids.add(b.budgetId());
    }
    Map<String, Mode> current;
    try {
      current = modesOf(request.orgId(), ids, now);
    } catch (RuntimeException shardDown) {
      return failPolicy(group, groupHolds, ids, sequence);
    }
    if (current.containsValue(Mode.DENY)) {
      return Decision.refused("deny");
    }
    if (current.values().stream().allMatch(m -> m == Mode.LEASE)
        && consumeLeases(groupHolds, now)) {
      return new Decision(true, Decision.Path.LEASE, ids, sequence, "");
    }
    ReserveResult r;
    try {
      r = store.reserve(request.requestId(), sequence, groupHolds);
    } catch (IllegalArgumentException rejected) {
      throw rejected; // a bad request, not an outage
    } catch (RuntimeException shardDown) {
      return failPolicy(group, groupHolds, ids, sequence);
    }
    return r.admitted()
        ? new Decision(true, Decision.Path.DIRECT, ids, sequence, "")
        : Decision.refused(r.reason().code());
  }

  private Map<String, Mode> modesOf(String orgId, List<String> ids, long now) {
    Map<String, Mode> out = new LinkedHashMap<>();
    boolean refreshed = false;
    for (String id : ids) {
      Optional<Mode> m = modes.mode(id, now);
      if (m.isEmpty() && !refreshed) {
        modes.update(store.modes(orgId), now);
        refreshed = true;
        m = modes.mode(id, now);
      }
      // A budget the shard does not report is reserved directly; the shard refuses it if unknown.
      out.put(id, m.orElse(Mode.DIRECT));
    }
    return out;
  }

  /** Spends every hold from its local lease, or none: partial spends are refunded. */
  private boolean consumeLeases(Map<String, Long> groupHolds, long now) {
    List<Map.Entry<String, Long>> taken = new ArrayList<>();
    for (Map.Entry<String, Long> e : groupHolds.entrySet()) {
      if (!lease(e.getKey()).tryConsume(e.getValue(), now)) {
        taken.forEach(t -> lease(t.getKey()).refund(t.getValue(), now));
        return false;
      }
      taken.add(e);
    }
    return true;
  }

  /** The shard is unreachable: admit only if every budget fails open within its allowance. */
  private Decision failPolicy(
      List<BudgetRef> group, Map<String, Long> groupHolds, List<String> ids, int sequence) {
    for (BudgetRef b : group) {
      if (!b.failPolicy().isOpen()) {
        return Decision.refused("shard_unavailable");
      }
    }
    List<BudgetRef> charged = new ArrayList<>();
    for (BudgetRef b : group) {
      long amount = groupHolds.get(b.budgetId());
      if (!tryFailOpen(b, amount)) {
        charged.forEach(
            c -> failOpenCounter(c.budgetId()).addAndGet(-groupHolds.get(c.budgetId())));
        return Decision.refused("shard_unavailable");
      }
      charged.add(b);
    }
    return new Decision(true, Decision.Path.FAIL_OPEN, ids, sequence, "");
  }

  private boolean tryFailOpen(BudgetRef b, long amount) {
    AtomicLong used = failOpenCounter(b.budgetId());
    long cap = b.failPolicy().failOpenAllowanceCents();
    while (true) {
      long u = used.get();
      if (u + amount > cap) {
        return false;
      }
      if (used.compareAndSet(u, u + amount)) {
        return true;
      }
    }
  }

  private AtomicLong failOpenCounter(String budgetId) {
    return failOpenUsed.computeIfAbsent(budgetId, id -> new AtomicLong());
  }

  /**
   * Asks the shard for a lease of {@code requested} on {@code budgetId} and installs it.
   *
   * @return the amount granted (0 for a stale epoch or no credit)
   */
  public long refillLease(String budgetId, long requested) {
    LocalLease lease = lease(budgetId);
    LeaseGrant g = store.grantLease(hostId, budgetId, requested, lease.epoch());
    if (g.staleEpoch()) {
      // Our epoch is behind: adopt the shard's epoch and ask again next time.
      report(budgetId, lease.install(0, g.epoch(), 0, 0));
      return 0;
    }
    report(
        budgetId,
        lease.install(g.granted(), g.epoch(), clock.millis() + LEASE_TTL_MILLIS, g.leaseId()));
    return g.granted();
  }

  /**
   * Ends every local lease and reports it to the shard (host shutdown), so unused credit is free at
   * once instead of after the TTL.
   */
  public void returnLeases() {
    leases.forEach((budgetId, lease) -> report(budgetId, lease.close()));
  }

  /** Lease reports the shard did not take (it expires those leases at their TTL). */
  public long unreportedLeases() {
    return unreportedLeases.get();
  }

  /**
   * Reports a lease taken off this host. Best effort: when the shard is unreachable the lease is
   * charged in full at its TTL and reconciliation corrects it, so admission never waits on this.
   */
  private void report(String budgetId, LocalLease.Closed closed) {
    if (closed.leaseId() == 0) {
      return;
    }
    try {
      store.reportLease(hostId, budgetId, closed.leaseId(), closed.spent(), closed.unused());
    } catch (RuntimeException e) {
      unreportedLeases.incrementAndGet();
    }
  }

  /** The local lease on {@code budgetId}. */
  public LocalLease lease(String budgetId) {
    return leases.computeIfAbsent(budgetId, LocalLease::new);
  }

  /** Fail-open spend admitted for {@code budgetId} during shard outages. */
  public long failOpenUsed(String budgetId) {
    return failOpenCounter(budgetId).get();
  }

  /** A budget changed (pushed on {@code budget.changes}): drop a cached {@code DENY}. */
  public void onBudgetChange(String budgetId) {
    modes.onBudgetChange(budgetId);
  }

  /** Test hook: caches a mode for a budget as if just fetched. */
  void forceMode(String budgetId, Mode mode) {
    modes.put(budgetId, mode, clock.millis());
  }
}
