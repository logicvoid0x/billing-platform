package com.logicvoid0x.billing.usageaggregator.sink;

import com.logicvoid0x.billing.usageaggregator.HourlyUsage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.flink.core.io.SimpleVersionedSerializer;

/**
 * The increments one sink subtask sealed at one checkpoint barrier, summed per (org, SKU, hour).
 * Flink keeps it in checkpointed state until the committer has applied it.
 *
 * @param batchId idempotency key: {@code job:subtask:barrier}
 * @param increments one increment per (org, SKU, hour)
 */
public record HourlyBatch(String batchId, List<HourlyUsage> increments) {
  /** Copies the increments. */
  public HourlyBatch {
    Objects.requireNonNull(batchId, "batchId");
    increments = List.copyOf(increments);
  }

  /** Binary form for Flink state; version 1. */
  public static final class Serializer implements SimpleVersionedSerializer<HourlyBatch> {
    @Override
    public int getVersion() {
      return 1;
    }

    @Override
    public byte[] serialize(HourlyBatch batch) throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        out.writeUTF(batch.batchId());
        out.writeInt(batch.increments().size());
        for (HourlyUsage u : batch.increments()) {
          out.writeUTF(u.orgId());
          out.writeUTF(u.skuId());
          out.writeLong(u.eventHour().getEpochSecond());
          out.writeLong(u.quantity());
          out.writeLong(u.lines());
        }
      }
      return bytes.toByteArray();
    }

    @Override
    public HourlyBatch deserialize(int version, byte[] serialized) throws IOException {
      if (version != 1) {
        throw new IOException("unknown HourlyBatch version " + version);
      }
      try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(serialized))) {
        String id = in.readUTF();
        int n = in.readInt();
        List<HourlyUsage> increments = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
          increments.add(
              new HourlyUsage(
                  in.readUTF(),
                  in.readUTF(),
                  Instant.ofEpochSecond(in.readLong()),
                  in.readLong(),
                  in.readLong()));
        }
        return new HourlyBatch(id, increments);
      }
    }
  }
}
