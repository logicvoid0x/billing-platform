package com.logicvoid0x.billing.budgetshard.ratis;

import com.logicvoid0x.billing.budgetshard.ratis.ShardCodec.Op;
import com.logicvoid0x.billing.domain.budget.BudgetBalance;
import com.logicvoid0x.billing.domain.budget.BudgetStore;
import com.logicvoid0x.billing.domain.budget.LeaseGrant;
import com.logicvoid0x.billing.domain.budget.ModeSnapshot;
import com.logicvoid0x.billing.domain.budget.ReserveResult;
import com.logicvoid0x.billing.domain.budget.SettleResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;

/** {@link BudgetStore} backed by a Ratis group: writes go through the Raft log. */
public final class RatisBudgetStore implements BudgetStore, AutoCloseable {
  private final RaftClient client;

  /** Wraps a client for the shard's Raft group. */
  public RatisBudgetStore(RaftClient client) {
    this.client = client;
  }

  /** Opens a budget with {@code capacity}. */
  public void open(String budgetId, String orgId, long capacity) {
    ShardCodec.readEmpty(
        write(ShardCodec.command(Op.OPEN, budgetId, orgId, 0, capacity, 0, Map.of())));
  }

  /** Adds capacity to a budget. */
  public void addCapacity(String budgetId, long delta) {
    ShardCodec.readEmpty(
        write(ShardCodec.command(Op.ADD_CAPACITY, budgetId, "", 0, delta, 0, Map.of())));
  }

  /** Moves the shard to a newer epoch. */
  public void advanceEpoch(long epoch) {
    ShardCodec.readEmpty(
        write(ShardCodec.command(Op.ADVANCE_EPOCH, "", "", 0, epoch, 0, Map.of())));
  }

  /** Reads one budget (linearizable read through the leader). */
  public BudgetBalance balance(String budgetId) {
    return ShardCodec.readBalance(
        read(ShardCodec.command(Op.QUERY_BALANCE, budgetId, "", 0, 0, 0, Map.of())));
  }

  @Override
  public ReserveResult reserve(String requestId, int sequence, Map<String, Long> holds) {
    return ShardCodec.readReserveResult(
        write(ShardCodec.command(Op.RESERVE, requestId, "", sequence, 0, 0, holds)));
  }

  @Override
  public ReserveResult extend(String requestId, int sequence, Map<String, Long> holds) {
    return ShardCodec.readReserveResult(
        write(ShardCodec.command(Op.EXTEND, requestId, "", sequence, 0, 0, holds)));
  }

  @Override
  public SettleResult settle(String requestId, int sequence, Map<String, Long> actual) {
    return ShardCodec.readSettleResult(
        write(ShardCodec.command(Op.SETTLE, requestId, "", sequence, 0, 0, actual)));
  }

  @Override
  public LeaseGrant grantLease(String hostId, String budgetId, long requested, long epoch) {
    return ShardCodec.readLeaseGrant(
        write(ShardCodec.command(Op.GRANT_LEASE, hostId, budgetId, 0, requested, epoch, Map.of())));
  }

  @Override
  public ModeSnapshot modes(String orgId) {
    return ShardCodec.readModes(
        read(ShardCodec.command(Op.QUERY_MODES, orgId, "", 0, 0, 0, Map.of())));
  }

  private byte[] write(byte[] command) {
    try {
      return reply(client.io().send(Message.valueOf(ByteString.copyFrom(command))));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private byte[] read(byte[] query) {
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
