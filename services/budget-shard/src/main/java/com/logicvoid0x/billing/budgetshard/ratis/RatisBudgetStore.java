package com.logicvoid0x.billing.budgetshard.ratis;

import com.logicvoid0x.billing.budgetshard.ratis.ShardCodec.Op;
import com.logicvoid0x.billing.domain.budget.BudgetBalance;
import com.logicvoid0x.billing.domain.budget.BudgetStore;
import com.logicvoid0x.billing.domain.budget.LeaseGrant;
import com.logicvoid0x.billing.domain.budget.LeaseReport;
import com.logicvoid0x.billing.domain.budget.ModeSnapshot;
import com.logicvoid0x.billing.domain.budget.ReserveResult;
import com.logicvoid0x.billing.domain.budget.Rollup;
import com.logicvoid0x.billing.domain.budget.SettleResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;

/**
 * {@link BudgetStore} backed by a Ratis group: writes go through the Raft log. Each command is
 * stamped with this client's clock; the stamp is part of the log entry, so every replica applies
 * the same time (and the state never moves time backwards).
 */
public final class RatisBudgetStore implements BudgetStore, AutoCloseable {
  private final RaftClient client;
  private final Clock clock;

  /** Wraps a client for the shard's Raft group, stamping commands with the UTC system clock. */
  public RatisBudgetStore(RaftClient client) {
    this(client, Clock.systemUTC());
  }

  /** Wraps a client for the shard's Raft group, stamping commands with {@code clock}. */
  public RatisBudgetStore(RaftClient client, Clock clock) {
    this.client = Objects.requireNonNull(client, "client");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Opens a budget with {@code capacity}. */
  public void open(String budgetId, String orgId, long capacity) {
    ShardCodec.readEmpty(write(Op.OPEN, budgetId, orgId, 0, capacity, 0, Map.of()));
  }

  /** Adds capacity to a budget. */
  public void addCapacity(String budgetId, long delta) {
    ShardCodec.readEmpty(write(Op.ADD_CAPACITY, budgetId, "", 0, delta, 0, Map.of()));
  }

  /** Moves the shard to a newer epoch. */
  public void advanceEpoch(long epoch) {
    ShardCodec.readEmpty(write(Op.ADVANCE_EPOCH, "", "", 0, epoch, 0, Map.of()));
  }

  /** Re-evaluates every mode at the current time (burn decays out of the window). */
  public void tick() {
    ShardCodec.readEmpty(write(Op.TICK, "", "", 0, 0, 0, Map.of()));
  }

  /**
   * Drains settled usage for the rollup window ending at {@code windowEndMillis}, through the log;
   * the same window end replays the same rollups.
   */
  public List<Rollup> rollup(String region, long windowEndMillis) {
    return ShardCodec.readRollups(write(Op.ROLLUP, region, "", 0, windowEndMillis, 0, Map.of()));
  }

  /** Corrects a counter by {@code delta} through the log, once per {@code key}. */
  public boolean adjust(String budgetId, long delta, String key) {
    return ShardCodec.readBool(write(Op.ADJUST, budgetId, key, 0, delta, 0, Map.of()));
  }

  /** Reads one budget (linearizable read through the leader). */
  public BudgetBalance balance(String budgetId) {
    return ShardCodec.readBalance(read(Op.QUERY_BALANCE, budgetId));
  }

  @Override
  public ReserveResult reserve(String requestId, int sequence, Map<String, Long> holds) {
    return ShardCodec.readReserveResult(write(Op.RESERVE, requestId, "", sequence, 0, 0, holds));
  }

  @Override
  public ReserveResult extend(String requestId, int sequence, Map<String, Long> holds) {
    return ShardCodec.readReserveResult(write(Op.EXTEND, requestId, "", sequence, 0, 0, holds));
  }

  @Override
  public SettleResult settle(String requestId, int sequence, Map<String, Long> actual) {
    return ShardCodec.readSettleResult(write(Op.SETTLE, requestId, "", sequence, 0, 0, actual));
  }

  @Override
  public LeaseGrant grantLease(String hostId, String budgetId, long requested, long epoch) {
    return ShardCodec.readLeaseGrant(
        write(Op.GRANT_LEASE, hostId, budgetId, 0, requested, epoch, Map.of()));
  }

  @Override
  public LeaseReport reportLease(
      String hostId, String budgetId, long leaseId, long spent, long unused) {
    return ShardCodec.readLeaseReport(
        write(Op.REPORT_LEASE, hostId, budgetId, 0, spent, leaseId, Map.of("unused", unused)));
  }

  @Override
  public ModeSnapshot modes(String orgId) {
    return ShardCodec.readModes(read(Op.QUERY_MODES, orgId));
  }

  private byte[] write(
      Op op, String id, String other, int sequence, long amount, long epoch, Map<String, Long> m) {
    byte[] command = ShardCodec.command(op, id, other, sequence, amount, epoch, clock.millis(), m);
    try {
      return reply(client.io().send(Message.valueOf(ByteString.copyFrom(command))));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private byte[] read(Op op, String id) {
    byte[] query = ShardCodec.command(op, id, "", 0, 0, 0, 0, Map.of());
    try {
      return reply(client.io().sendReadOnly(Message.valueOf(ByteString.copyFrom(query))));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] reply(RaftClientReply reply) {
    if (!reply.isSuccess()) {
      throw new IllegalStateException("raft request failed: " + reply.getException());
    }
    return ShardCodec.requireOk(reply.getMessage().getContent().toByteArray());
  }

  @Override
  public void close() throws IOException {
    client.close();
  }
}
