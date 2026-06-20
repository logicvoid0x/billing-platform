package com.logicvoid0x.billing.budgetshard.ratis;

import com.logicvoid0x.billing.budgetshard.ratis.ShardCodec.Command;
import com.logicvoid0x.billing.domain.budget.BudgetState;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.MD5FileUtil;

/**
 * Raft state machine of a regional budget shard: applies {@link ShardCodec} commands from the log
 * to a {@link BudgetState}, in log order, on every replica. A command that the state rejects (for
 * example a reused idempotency key) yields an error result on every replica alike, so replicas
 * never diverge. Snapshots are {@link BudgetState#writeTo} files.
 */
public final class BudgetStateMachine extends BaseStateMachine {
  private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();
  private final long minHold;
  private BudgetState state;

  /**
   * Creates the state machine.
   *
   * @param minHold the smallest possible hold; below it a budget is in {@code DENY}
   */
  public BudgetStateMachine(long minHold) {
    this.minHold = minHold;
    this.state = new BudgetState(minHold);
  }

  @Override
  public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage raftStorage)
      throws IOException {
    super.initialize(server, groupId, raftStorage);
    storage.init(raftStorage);
    reinitialize();
  }

  @Override
  public void reinitialize() throws IOException {
    SingleFileSnapshotInfo snapshot = storage.loadLatestSnapshot();
    if (snapshot == null) {
      return;
    }
    File file = snapshot.getFile().getPath().toFile();
    MD5FileUtil.verifySavedMD5(file, snapshot.getFile().getFileDigest());
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(file.toPath())))) {
      BudgetState loaded = BudgetState.readFrom(in);
      synchronized (this) {
        state = loaded;
        updateLastAppliedTermIndex(snapshot.getTermIndex());
      }
    }
  }

  @Override
  public SimpleStateMachineStorage getStateMachineStorage() {
    return storage;
  }

  @Override
  public long takeSnapshot() throws IOException {
    TermIndex applied;
    File file;
    synchronized (this) {
      applied = getLastAppliedTermIndex();
      if (applied == null) {
        return -1;
      }
      file = storage.getSnapshotFile(applied.getTerm(), applied.getIndex());
      try (DataOutputStream out =
          new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file.toPath())))) {
        state.writeTo(out);
      }
    }
    FileInfo info = new FileInfo(file.toPath(), MD5FileUtil.computeAndSaveMd5ForFile(file));
    storage.updateLatestSnapshot(new SingleFileSnapshotInfo(info, applied));
    return applied.getIndex();
  }

  @Override
  public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
    LogEntryProto entry = trx.getLogEntry();
    byte[] command = entry.getStateMachineLogEntry().getLogData().toByteArray();
    byte[] reply;
    synchronized (this) {
      reply = apply(ShardCodec.readCommand(command));
      updateLastAppliedTermIndex(TermIndex.valueOf(entry));
    }
    return CompletableFuture.completedFuture(Message.valueOf(ByteString.copyFrom(reply)));
  }

  @Override
  public CompletableFuture<Message> query(Message request) {
    byte[] reply;
    synchronized (this) {
      reply = apply(ShardCodec.readCommand(request.getContent().toByteArray()));
    }
    return CompletableFuture.completedFuture(Message.valueOf(ByteString.copyFrom(reply)));
  }

  /** Applies one command; a deterministic rejection becomes an error result. */
  private byte[] apply(Command c) {
    try {
      if (c.op() != ShardCodec.Op.QUERY_BALANCE && c.op() != ShardCodec.Op.QUERY_MODES) {
        state.observeTime(c.atMillis()); // time comes from the log entry, never a local clock
      }
      return switch (c.op()) {
        case OPEN -> {
          state.open(c.id(), c.other(), c.amount());
          yield ShardCodec.empty();
        }
        case ADD_CAPACITY -> {
          state.addCapacity(c.id(), c.amount());
          yield ShardCodec.empty();
        }
        case ADVANCE_EPOCH -> {
          state.advanceEpoch(c.amount());
          yield ShardCodec.empty();
        }
        case RESERVE -> ShardCodec.reserveResult(state.reserve(c.id(), c.sequence(), c.amounts()));
        case EXTEND -> ShardCodec.reserveResult(state.extend(c.id(), c.sequence(), c.amounts()));
        case SETTLE -> ShardCodec.settleResult(state.settle(c.id(), c.sequence(), c.amounts()));
        case ROLLUP -> ShardCodec.rollups(state.rollup(c.id(), c.amount()));
        case TICK -> {
          state.tick(c.atMillis());
          yield ShardCodec.empty();
        }
        case GRANT_LEASE ->
            ShardCodec.leaseGrant(state.grantLease(c.id(), c.other(), c.amount(), c.epoch()));
        case QUERY_BALANCE -> ShardCodec.balance(state.balance(c.id()));
        case QUERY_MODES -> ShardCodec.modes(state.modes(c.id()));
      };
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException e) {
      return ShardCodec.error(e.getMessage());
    }
  }

  /** Reads one budget on this replica (for tests and diagnostics; may lag the leader). */
  public synchronized com.logicvoid0x.billing.domain.budget.BudgetBalance localBalance(
      String budgetId) {
    return state.balance(budgetId);
  }

  /** Index of the last log entry applied on this replica, or -1 before any. */
  public synchronized long lastAppliedIndex() {
    TermIndex t = getLastAppliedTermIndex();
    return t == null ? -1 : t.getIndex();
  }

  /** The smallest possible hold this machine was created with. */
  public long minHold() {
    return minHold;
  }
}
