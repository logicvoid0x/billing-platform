package com.logicvoid0x.billing.domain.budget;

import com.logicvoid0x.billing.domain.Mode;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The shard's state and its deterministic transitions. Not thread-safe: callers serialize access (a
 * lock in {@link InMemoryBudgetStore}, the Raft log in the Ratis state machine). Applying the same
 * operations in the same order always gives the same state and results.
 */
public final class BudgetState {
  private static final class Account {
    final String orgId;
    long capacity;
    long spent;
    long held;
    long leased;
    // Settled usage not yet sent to the home ledger (negative after a downward correction).
    long unrolled;
    Mode mode = Mode.DIRECT;
    final BurnMeter burn = new BurnMeter(BURN_WINDOW_MILLIS, BURN_BUCKET_MILLIS);

    Account(String orgId, long capacity) {
      this.orgId = orgId;
      this.capacity = capacity;
    }
  }

  /** Burn rate is measured over the last minute, in 10 s buckets. */
  static final long BURN_WINDOW_MILLIS = 60_000;

  static final long BURN_BUCKET_MILLIS = 10_000;

  private static final class Reservation {
    final Map<String, Long> held = new TreeMap<>();
    long openedAtMillis;
    boolean settled;
    // Settled by tick at the held amount; the first real settle afterwards corrects it.
    boolean autoSettled;
  }

  /** Default max request duration (10 min) plus grace (1 min) before a hold auto-settles. */
  public static final long DEFAULT_MAX_HOLD_MILLIS = 660_000;

  private final long minHold;
  private final long maxHoldMillis;
  private final ModePolicy policy;
  private long epoch;
  // Latest time seen on a command (epoch millis); never read from a clock, so replicas agree.
  private long nowMillis;
  private final Map<String, Account> accounts = new TreeMap<>();
  private final Map<String, Reservation> reservations = new TreeMap<>();
  private final Map<String, Map<String, Long>> leases = new HashMap<>();
  // requestId:sequence -> (payload, result): replays return the first result.
  private final Map<String, Object[]> applied = new HashMap<>();
  private long lastRollupEndMillis;
  private List<Rollup> lastRollup = List.of();

  /**
   * Creates an empty state with the default auto-settle delay.
   *
   * @param minHold the smallest possible hold; below it a budget is in {@code DENY}
   */
  public BudgetState(long minHold) {
    this(minHold, DEFAULT_MAX_HOLD_MILLIS);
  }

  /**
   * Creates an empty state.
   *
   * @param minHold the smallest possible hold; below it a budget is in {@code DENY}
   * @param maxHoldMillis max request duration plus grace; older holds auto-settle on tick
   */
  public BudgetState(long minHold, long maxHoldMillis) {
    if (minHold <= 0 || maxHoldMillis <= 0) {
      throw new IllegalArgumentException("minHold and maxHoldMillis must be > 0");
    }
    this.minHold = minHold;
    this.maxHoldMillis = maxHoldMillis;
    this.policy = ModePolicy.standard(minHold);
  }

  /**
   * Moves the state's notion of time forward to {@code atMillis} (the time stamped on the command
   * being applied). Time never goes backwards; an older stamp is ignored.
   */
  public void observeTime(long atMillis) {
    nowMillis = Math.max(nowMillis, atMillis);
  }

  /**
   * Periodic maintenance at {@code atMillis}: auto-settles holds open for at least the max hold
   * time at their held amount (the gateway that held them is presumed gone), then re-evaluates
   * every budget's mode so idle burn decays out of the window.
   */
  public void tick(long atMillis) {
    observeTime(atMillis);
    for (Reservation r : reservations.values()) {
      if (!r.settled && nowMillis - r.openedAtMillis >= maxHoldMillis) {
        r.held.forEach((budgetId, amount) -> {
          Account a = account(budgetId);
          a.held -= amount;
          charge(a, amount);
        });
        r.settled = true;
        r.autoSettled = true;
      }
    }
    accounts.values().forEach(this::reevaluate);
  }

  /**
   * Drains settled usage per budget for the rollup window ending at {@code windowEndMillis}.
   * Idempotent on the window: the same window end returns the same rollups again, and an older one
   * returns nothing.
   *
   * @param region this shard's region, part of each rollup's idempotency key
   * @param windowEndMillis end of the window, epoch millis (typically every 5 s)
   */
  public List<Rollup> rollup(String region, long windowEndMillis) {
    Objects.requireNonNull(region, "region");
    if (windowEndMillis == lastRollupEndMillis) {
      return lastRollup;
    }
    if (windowEndMillis < lastRollupEndMillis) {
      return List.of();
    }
    List<Rollup> out = new ArrayList<>();
    accounts.forEach((budgetId, a) -> {
      if (a.unrolled != 0) {
        out.add(
            new Rollup(
                a.orgId, budgetId, region, a.unrolled, lastRollupEndMillis, windowEndMillis));
        a.unrolled = 0;
      }
    });
    lastRollupEndMillis = windowEndMillis;
    lastRollup = List.copyOf(out);
    return lastRollup;
  }

  private void charge(Account a, long amount) {
    a.spent = Math.addExact(a.spent, amount);
    a.unrolled = Math.addExact(a.unrolled, amount);
    if (amount > 0) {
      a.burn.record(amount, nowMillis);
    }
  }

  /** Opens a budget on this shard with {@code capacity}. */
  public void open(String budgetId, String orgId, long capacity) {
    requireNonNegative(capacity);
    Account a = new Account(orgId, capacity);
    if (accounts.putIfAbsent(budgetId, a) != null) {
      throw new IllegalStateException("budget already open: " + budgetId);
    }
    reevaluate(a);
  }

  /** Adds capacity (an escrow grant or a raised cap). */
  public void addCapacity(String budgetId, long delta) {
    requireNonNegative(delta);
    Account a = account(budgetId);
    a.capacity = Math.addExact(a.capacity, delta);
    reevaluate(a);
  }

  /** Moves to a newer epoch (a new Raft leader term); older epochs are fenced. */
  public void advanceEpoch(long newEpoch) {
    if (newEpoch <= epoch) {
      throw new IllegalArgumentException("epoch must increase");
    }
    epoch = newEpoch;
  }

  /** The current epoch. */
  public long epoch() {
    return epoch;
  }

  /** Reads one budget. */
  public BudgetBalance balance(String budgetId) {
    Account a = account(budgetId);
    return new BudgetBalance(budgetId, a.orgId, a.capacity, a.spent, a.held, a.leased);
  }

  /** See {@link BudgetStore#reserve}. */
  public ReserveResult reserve(String requestId, int sequence, Map<String, Long> holds) {
    return idempotent(
        "reserve",
        requestId,
        sequence,
        holds,
        () -> {
          if (reservations.containsKey(requestId)) {
            throw new IllegalStateException("request already reserved: " + requestId);
          }
          ReserveResult check = checkHolds(holds);
          if (check.admitted()) {
            Reservation r = new Reservation();
            r.openedAtMillis = nowMillis;
            applyHolds(r, holds);
            reservations.put(requestId, r);
          }
          return check;
        });
  }

  /** See {@link BudgetStore#extend}. */
  public ReserveResult extend(String requestId, int sequence, Map<String, Long> holds) {
    return idempotent(
        "extend",
        requestId,
        sequence,
        holds,
        () -> {
          Reservation r = reservations.get(requestId);
          if (r == null || r.settled) {
            return ReserveResult.refused(ReserveResult.Reason.NO_RESERVATION, "");
          }
          ReserveResult check = checkHolds(holds);
          if (check.admitted()) {
            applyHolds(r, holds);
          }
          return check;
        });
  }

  /** See {@link BudgetStore#settle}. */
  public SettleResult settle(String requestId, int sequence, Map<String, Long> actual) {
    return idempotent(
        "settle",
        requestId,
        sequence,
        actual,
        () -> {
          Reservation r = reservations.get(requestId);
          if (r == null) {
            return new SettleResult(SettleResult.Status.NO_RESERVATION, Map.of());
          }
          if (r.settled && !r.autoSettled) {
            return new SettleResult(SettleResult.Status.ALREADY_SETTLED, Map.of());
          }
          for (String budgetId : actual.keySet()) {
            if (!r.held.containsKey(budgetId)) {
              throw new IllegalArgumentException("budget not in the reservation: " + budgetId);
            }
          }
          Map<String, Long> released = new TreeMap<>();
          for (Map.Entry<String, Long> e : r.held.entrySet()) {
            long spend = actual.getOrDefault(e.getKey(), 0L);
            Account a = account(e.getKey());
            if (r.autoSettled) {
              // Already charged at the held amount: post only the difference.
              charge(a, spend - e.getValue());
            } else {
              a.held -= e.getValue();
              charge(a, spend);
            }
            released.put(e.getKey(), Math.max(0, e.getValue() - spend));
            reevaluate(a);
          }
          r.settled = true;
          r.autoSettled = false;
          return new SettleResult(SettleResult.Status.SETTLED, released);
        });
  }

  /** See {@link BudgetStore#grantLease}. */
  public LeaseGrant grantLease(String hostId, String budgetId, long requested, long requestEpoch) {
    Objects.requireNonNull(hostId, "hostId");
    requireNonNegative(requested);
    if (requestEpoch < epoch) {
      return new LeaseGrant(0, epoch, true);
    }
    Account a = account(budgetId);
    long granted = Math.max(0, Math.min(requested, available(a)));
    a.leased = Math.addExact(a.leased, granted);
    leases.computeIfAbsent(hostId, h -> new TreeMap<>()).merge(budgetId, granted, Math::addExact);
    reevaluate(a);
    return new LeaseGrant(granted, epoch, false);
  }

  /**
   * See {@link BudgetStore#modes}. A pure read of the stored modes, which {@link ModePolicy}
   * re-evaluates on every write and {@link #tick}: {@code DENY} below the smallest hold, {@code
   * LEASE} with enough runway (60 s / 120 s hysteresis), else {@code DIRECT}.
   */
  public ModeSnapshot modes(String orgId) {
    Map<String, Mode> modes = new TreeMap<>();
    accounts.forEach((id, a) -> {
      if (a.orgId.equals(orgId)) {
        modes.put(id, a.mode);
      }
    });
    return new ModeSnapshot(orgId, modes, epoch);
  }

  private void reevaluate(Account a) {
    a.mode = policy.next(a.mode, available(a), a.burn.perSecond(nowMillis));
  }

  private ReserveResult checkHolds(Map<String, Long> holds) {
    for (Map.Entry<String, Long> e : new TreeMap<>(holds).entrySet()) {
      requireNonNegative(e.getValue());
      Account a = accounts.get(e.getKey());
      if (a == null) {
        return ReserveResult.refused(ReserveResult.Reason.UNKNOWN_BUDGET, e.getKey());
      }
      if (available(a) < e.getValue()) {
        return ReserveResult.refused(ReserveResult.Reason.INSUFFICIENT, e.getKey());
      }
    }
    return ReserveResult.ok();
  }

  private void applyHolds(Reservation r, Map<String, Long> holds) {
    holds.forEach((budgetId, amount) -> {
      Account a = account(budgetId);
      a.held = Math.addExact(a.held, amount);
      r.held.merge(budgetId, amount, Math::addExact);
      reevaluate(a);
    });
  }

  private <T> T idempotent(
      String op,
      String requestId,
      int sequence,
      Map<String, Long> payload,
      java.util.function.Supplier<T> apply) {
    Objects.requireNonNull(requestId, "requestId");
    payload.values().forEach(BudgetState::requireNonNegative);
    String key = op + ":" + requestId + ":" + sequence;
    Object[] seen = applied.get(key);
    Map<String, Long> copy = Map.copyOf(payload);
    if (seen != null) {
      if (!seen[0].equals(copy)) {
        throw new IllegalArgumentException(
            "idempotency key reused with a different payload: " + key);
      }
      @SuppressWarnings("unchecked")
      T result = (T) seen[1];
      return result;
    }
    T result = apply.get();
    applied.put(key, new Object[] {copy, result});
    return result;
  }

  private Account account(String budgetId) {
    Account a = accounts.get(budgetId);
    if (a == null) {
      throw new IllegalArgumentException("unknown budget: " + budgetId);
    }
    return a;
  }

  private static long available(Account a) {
    return a.capacity - a.spent - a.held - a.leased;
  }

  private static void requireNonNegative(long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException("amounts must be >= 0");
    }
  }

  // ------------------------------------------------------------------ snapshots

  private static final int SNAPSHOT_FORMAT = 3;
  private static final byte RESERVE_RESULT = 1;
  private static final byte SETTLE_RESULT = 2;

  /**
   * Writes the whole state, including the idempotency table, so a restored shard still recognizes
   * replays. Maps are written in key order, so equal states give equal bytes.
   */
  public void writeTo(DataOutputStream out) throws IOException {
    out.writeInt(SNAPSHOT_FORMAT);
    out.writeLong(minHold);
    out.writeLong(maxHoldMillis);
    out.writeLong(epoch);
    out.writeLong(nowMillis);
    out.writeInt(accounts.size());
    for (Map.Entry<String, Account> e : accounts.entrySet()) {
      Account a = e.getValue();
      out.writeUTF(e.getKey());
      out.writeUTF(a.orgId);
      out.writeLong(a.capacity);
      out.writeLong(a.spent);
      out.writeLong(a.held);
      out.writeLong(a.leased);
      out.writeLong(a.unrolled);
      out.writeUTF(a.mode.code());
      Map<Long, Long> burn = a.burn.buckets();
      out.writeInt(burn.size());
      for (Map.Entry<Long, Long> b : burn.entrySet()) {
        out.writeLong(b.getKey());
        out.writeLong(b.getValue());
      }
    }
    Map<String, Reservation> sortedReservations = new TreeMap<>(reservations);
    out.writeInt(sortedReservations.size());
    for (Map.Entry<String, Reservation> e : sortedReservations.entrySet()) {
      out.writeUTF(e.getKey());
      out.writeBoolean(e.getValue().settled);
      out.writeBoolean(e.getValue().autoSettled);
      out.writeLong(e.getValue().openedAtMillis);
      writeAmounts(out, e.getValue().held);
    }
    Map<String, Map<String, Long>> sortedLeases = new TreeMap<>(leases);
    out.writeInt(sortedLeases.size());
    for (Map.Entry<String, Map<String, Long>> e : sortedLeases.entrySet()) {
      out.writeUTF(e.getKey());
      writeAmounts(out, e.getValue());
    }
    Map<String, Object[]> sortedApplied = new TreeMap<>(applied);
    out.writeInt(sortedApplied.size());
    for (Map.Entry<String, Object[]> e : sortedApplied.entrySet()) {
      out.writeUTF(e.getKey());
      @SuppressWarnings("unchecked")
      Map<String, Long> payload = (Map<String, Long>) e.getValue()[0];
      writeAmounts(out, payload);
      Object result = e.getValue()[1];
      if (result instanceof ReserveResult r) {
        out.writeByte(RESERVE_RESULT);
        out.writeBoolean(r.admitted());
        out.writeUTF(r.reason().code());
        out.writeUTF(r.refusedBudgetId());
      } else if (result instanceof SettleResult s) {
        out.writeByte(SETTLE_RESULT);
        out.writeUTF(s.status().code());
        writeAmounts(out, s.released());
      } else {
        throw new IllegalStateException("unexpected result type " + result);
      }
    }
    out.writeLong(lastRollupEndMillis);
    out.writeInt(lastRollup.size());
    for (Rollup r : lastRollup) {
      out.writeUTF(r.orgId());
      out.writeUTF(r.budgetId());
      out.writeUTF(r.region());
      out.writeLong(r.amount());
      out.writeLong(r.windowStartMillis());
      out.writeLong(r.windowEndMillis());
    }
  }

  /** Reads a state written by {@link #writeTo}. */
  public static BudgetState readFrom(DataInputStream in) throws IOException {
    int format = in.readInt();
    if (format != SNAPSHOT_FORMAT) {
      throw new IOException("unsupported snapshot format " + format);
    }
    BudgetState s = new BudgetState(in.readLong(), in.readLong());
    s.epoch = in.readLong();
    s.nowMillis = in.readLong();
    for (int i = in.readInt(); i > 0; i--) {
      String id = in.readUTF();
      Account a = new Account(in.readUTF(), in.readLong());
      a.spent = in.readLong();
      a.held = in.readLong();
      a.leased = in.readLong();
      a.unrolled = in.readLong();
      a.mode = Mode.fromCode(in.readUTF());
      Map<Long, Long> burn = new TreeMap<>();
      for (int j = in.readInt(); j > 0; j--) {
        burn.put(in.readLong(), in.readLong());
      }
      a.burn.restore(burn);
      s.accounts.put(id, a);
    }
    for (int i = in.readInt(); i > 0; i--) {
      String id = in.readUTF();
      Reservation r = new Reservation();
      r.settled = in.readBoolean();
      r.autoSettled = in.readBoolean();
      r.openedAtMillis = in.readLong();
      r.held.putAll(readAmounts(in));
      s.reservations.put(id, r);
    }
    for (int i = in.readInt(); i > 0; i--) {
      String host = in.readUTF();
      s.leases.put(host, new TreeMap<>(readAmounts(in)));
    }
    for (int i = in.readInt(); i > 0; i--) {
      String key = in.readUTF();
      Map<String, Long> payload = Map.copyOf(readAmounts(in));
      byte type = in.readByte();
      Object result;
      if (type == RESERVE_RESULT) {
        result =
            new ReserveResult(
                in.readBoolean(), ReserveResult.Reason.fromCode(in.readUTF()), in.readUTF());
      } else if (type == SETTLE_RESULT) {
        result = new SettleResult(SettleResult.Status.fromCode(in.readUTF()), readAmounts(in));
      } else {
        throw new IOException("unknown result type " + type);
      }
      s.applied.put(key, new Object[] {payload, result});
    }
    s.lastRollupEndMillis = in.readLong();
    List<Rollup> rollups = new ArrayList<>();
    for (int i = in.readInt(); i > 0; i--) {
      rollups.add(
          new Rollup(
              in.readUTF(),
              in.readUTF(),
              in.readUTF(),
              in.readLong(),
              in.readLong(),
              in.readLong()));
    }
    s.lastRollup = List.copyOf(rollups);
    return s;
  }

  private static void writeAmounts(DataOutputStream out, Map<String, Long> amounts)
      throws IOException {
    Map<String, Long> sorted = new TreeMap<>(amounts);
    out.writeInt(sorted.size());
    for (Map.Entry<String, Long> e : sorted.entrySet()) {
      out.writeUTF(e.getKey());
      out.writeLong(e.getValue());
    }
  }

  private static Map<String, Long> readAmounts(DataInputStream in) throws IOException {
    Map<String, Long> m = new TreeMap<>();
    for (int i = in.readInt(); i > 0; i--) {
      m.put(in.readUTF(), in.readLong());
    }
    return m;
  }
}
