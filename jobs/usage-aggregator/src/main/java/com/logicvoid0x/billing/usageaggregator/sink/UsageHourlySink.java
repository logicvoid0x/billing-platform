package com.logicvoid0x.billing.usageaggregator.sink;

import com.logicvoid0x.billing.usageaggregator.HourlyUsage;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.flink.api.connector.sink2.Committer;
import org.apache.flink.api.connector.sink2.CommitterInitContext;
import org.apache.flink.api.connector.sink2.CommittingSinkWriter;
import org.apache.flink.api.connector.sink2.InitContext;
import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.api.connector.sink2.SupportsCommitter;
import org.apache.flink.api.connector.sink2.WriterInitContext;
import org.apache.flink.core.io.SimpleVersionedSerializer;

/**
 * Checkpoint-aligned sink of hourly increments into {@code metering.usage_hourly} (design: "one
 * batch per checkpoint").
 *
 * <ol>
 *   <li>The writer sums increments per (org, SKU, hour) between barriers.
 *   <li>At barrier N it seals them into an {@link HourlyBatch}; Flink stores the batch in
 *       checkpoint N's state.
 *   <li>After checkpoint N completes, the committer applies the batch in one short transaction:
 *       insert its {@code applied_batches} row and add every increment. If the row already exists
 *       the batch was applied before and the transaction is rolled back. After a failure Flink
 *       re-commits batches from the restored checkpoint, which is therefore safe.
 * </ol>
 *
 * <p>Batch IDs: {@code job:jobId:subtask:barrier}. The Flink job ID stays the same across failover
 * restores of a run and changes for every new submission (a fresh start or a savepoint restore), so
 * IDs never collide across runs. Within a run the barrier counter restarts above the restored
 * checkpoint ID: a batch can only have been applied if a checkpoint at or after its barrier
 * completed, so after restoring checkpoint K every new ID is above every applied one. Restored
 * batches keep their original IDs, so re-committing them is a no-op. No part of the ID comes from
 * wall-clock time.
 */
public final class UsageHourlySink implements Sink<HourlyUsage>, SupportsCommitter<HourlyBatch> {
  private static final long serialVersionUID = 1L;

  private final String jobName;
  private final Jdbc jdbc;

  /** Creates the sink. */
  public UsageHourlySink(String jobName, Jdbc jdbc) {
    this.jobName = Objects.requireNonNull(jobName, "jobName");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public SinkWriter<HourlyUsage> createWriter(WriterInitContext context) {
    long restored = context.getRestoredCheckpointId().orElse(InitContext.INITIAL_CHECKPOINT_ID - 1);
    return new Writer(
        jobName
            + ":"
            + context.getJobInfo().getJobId()
            + ":"
            + context.getTaskInfo().getIndexOfThisSubtask()
            + ":",
        restored + 1);
  }

  @Override
  public Committer<HourlyBatch> createCommitter(CommitterInitContext context) {
    return new JdbcCommitter(jdbc);
  }

  @Override
  public SimpleVersionedSerializer<HourlyBatch> getCommittableSerializer() {
    return new HourlyBatch.Serializer();
  }

  /** Sums increments between barriers and seals them into a batch at each barrier. */
  static final class Writer implements CommittingSinkWriter<HourlyUsage, HourlyBatch> {
    private final String idPrefix;
    private long nextBarrier;
    private final Map<String, HourlyUsage> pending = new LinkedHashMap<>();

    Writer(String idPrefix, long firstBarrier) {
      this.idPrefix = idPrefix;
      this.nextBarrier = firstBarrier;
    }

    @Override
    public void write(HourlyUsage u, Context context) {
      pending.merge(
          u.key(),
          u,
          (a, b) ->
              new HourlyUsage(
                  a.orgId(),
                  a.skuId(),
                  a.eventHour(),
                  Math.addExact(a.quantity(), b.quantity()),
                  Math.addExact(a.lines(), b.lines())));
    }

    @Override
    public void flush(boolean endOfInput) {}

    @Override
    public Collection<HourlyBatch> prepareCommit() {
      String id = idPrefix + nextBarrier++;
      if (pending.isEmpty()) {
        return List.of();
      }
      HourlyBatch batch = new HourlyBatch(id, new ArrayList<>(pending.values()));
      pending.clear();
      return List.of(batch);
    }

    @Override
    public void close() {}
  }

  /** Applies batches, each exactly once, in one transaction per batch. */
  static final class JdbcCommitter implements Committer<HourlyBatch> {
    private final Jdbc jdbc;

    JdbcCommitter(Jdbc jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public void commit(Collection<CommitRequest<HourlyBatch>> requests) throws IOException {
      for (CommitRequest<HourlyBatch> request : requests) {
        try {
          if (apply(jdbc, request.getCommittable())) {
            continue;
          }
          request.signalAlreadyCommitted();
        } catch (SQLException e) {
          // A database outage: retry the same batch; its ID keeps the retry idempotent.
          request.retryLater();
        }
      }
    }

    @Override
    public void close() {}
  }

  /**
   * Applies one batch.
   *
   * @return true if applied now, false if its ID was already applied
   */
  public static boolean apply(Jdbc jdbc, HourlyBatch batch) throws SQLException {
    try (Connection c = jdbc.connect()) {
      c.setAutoCommit(false);
      try {
        try (PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO metering.applied_batches (batch_id, increments) VALUES (?, ?)"
                    + " ON CONFLICT (batch_id) DO NOTHING")) {
          s.setString(1, batch.batchId());
          s.setInt(2, batch.increments().size());
          if (s.executeUpdate() == 0) {
            c.rollback();
            return false;
          }
        }
        try (PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO metering.usage_hourly (org_id, sku_id, event_hour, quantity, lines)"
                    + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (org_id, sku_id, event_hour) DO UPDATE"
                    + " SET quantity = usage_hourly.quantity + EXCLUDED.quantity,"
                    + " lines = usage_hourly.lines + EXCLUDED.lines, updated_at = now()")) {
          for (HourlyUsage u : batch.increments()) {
            s.setString(1, u.orgId());
            s.setString(2, u.skuId());
            s.setTimestamp(3, Timestamp.from(u.eventHour()));
            s.setLong(4, u.quantity());
            s.setLong(5, u.lines());
            s.addBatch();
          }
          s.executeBatch();
        }
        c.commit();
        return true;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }
}
