package com.logicvoid0x.billing.budgetshard.ratis;

import com.logicvoid0x.billing.domain.Mode;
import com.logicvoid0x.billing.domain.budget.BudgetBalance;
import com.logicvoid0x.billing.domain.budget.LeaseGrant;
import com.logicvoid0x.billing.domain.budget.ModeSnapshot;
import com.logicvoid0x.billing.domain.budget.ReserveResult;
import com.logicvoid0x.billing.domain.budget.SettleResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Binary encoding of shard commands (Raft log entries and read-only queries) and their results.
 * Every field is written explicitly in a fixed order; enums travel as their permanent codes.
 */
public final class ShardCodec {
  private ShardCodec() {}

  /** Command types; the byte value is permanent once written to a log. */
  public enum Op {
    OPEN(1),
    ADD_CAPACITY(2),
    ADVANCE_EPOCH(3),
    RESERVE(4),
    EXTEND(5),
    SETTLE(6),
    GRANT_LEASE(7),
    QUERY_BALANCE(20),
    QUERY_MODES(21);

    final byte tag;

    Op(int tag) {
      this.tag = (byte) tag;
    }

    static Op of(byte tag) {
      for (Op op : values()) {
        if (op.tag == tag) {
          return op;
        }
      }
      throw new IllegalArgumentException("unknown op " + tag);
    }
  }

  /** A decoded command. Unused fields are empty, zero or an empty map. */
  public record Command(
      Op op,
      String id,
      String other,
      int sequence,
      long amount,
      long epoch,
      Map<String, Long> amounts) {}

  static final byte OK = 0;
  static final byte ERROR = 1;

  // ------------------------------------------------------------------ commands

  /** Encodes a command. */
  public static byte[] command(
      Op op,
      String id,
      String other,
      int sequence,
      long amount,
      long epoch,
      Map<String, Long> amounts) {
    return write(out -> {
      out.writeByte(op.tag);
      out.writeUTF(id);
      out.writeUTF(other);
      out.writeInt(sequence);
      out.writeLong(amount);
      out.writeLong(epoch);
      writeAmounts(out, amounts);
    });
  }

  /** Decodes a command. */
  public static Command readCommand(byte[] bytes) {
    return read(
        bytes,
        in ->
            new Command(
                Op.of(in.readByte()),
                in.readUTF(),
                in.readUTF(),
                in.readInt(),
                in.readLong(),
                in.readLong(),
                readAmounts(in)));
  }

  // ------------------------------------------------------------------ results

  static byte[] error(String message) {
    return write(out -> {
      out.writeByte(ERROR);
      out.writeUTF(message == null ? "error" : message);
    });
  }

  static byte[] empty() {
    return write(out -> out.writeByte(OK));
  }

  static byte[] reserveResult(ReserveResult r) {
    return write(out -> {
      out.writeByte(OK);
      out.writeBoolean(r.admitted());
      out.writeUTF(r.reason().code());
      out.writeUTF(r.refusedBudgetId());
    });
  }

  static byte[] settleResult(SettleResult s) {
    return write(out -> {
      out.writeByte(OK);
      out.writeUTF(s.status().code());
      writeAmounts(out, s.released());
    });
  }

  static byte[] leaseGrant(LeaseGrant g) {
    return write(out -> {
      out.writeByte(OK);
      out.writeLong(g.granted());
      out.writeLong(g.epoch());
      out.writeBoolean(g.staleEpoch());
    });
  }

  static byte[] balance(BudgetBalance b) {
    return write(out -> {
      out.writeByte(OK);
      out.writeUTF(b.budgetId());
      out.writeUTF(b.orgId());
      out.writeLong(b.capacity());
      out.writeLong(b.spent());
      out.writeLong(b.held());
      out.writeLong(b.leased());
    });
  }

  static byte[] modes(ModeSnapshot m) {
    return write(out -> {
      out.writeByte(OK);
      out.writeUTF(m.orgId());
      out.writeLong(m.epoch());
      Map<String, Mode> sorted = new TreeMap<>(m.modeByBudget());
      out.writeInt(sorted.size());
      for (Map.Entry<String, Mode> e : sorted.entrySet()) {
        out.writeUTF(e.getKey());
        out.writeUTF(e.getValue().code());
      }
    });
  }

  static void readEmpty(byte[] bytes) {
    read(bytes, in -> null);
  }

  static ReserveResult readReserveResult(byte[] bytes) {
    return read(
        bytes,
        in ->
            new ReserveResult(
                in.readBoolean(), ReserveResult.Reason.fromCode(in.readUTF()), in.readUTF()));
  }

  static SettleResult readSettleResult(byte[] bytes) {
    return read(
        bytes, in -> new SettleResult(SettleResult.Status.fromCode(in.readUTF()), readAmounts(in)));
  }

  static LeaseGrant readLeaseGrant(byte[] bytes) {
    return read(bytes, in -> new LeaseGrant(in.readLong(), in.readLong(), in.readBoolean()));
  }

  static BudgetBalance readBalance(byte[] bytes) {
    return read(
        bytes,
        in ->
            new BudgetBalance(
                in.readUTF(),
                in.readUTF(),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                in.readLong()));
  }

  static ModeSnapshot readModes(byte[] bytes) {
    return read(
        bytes,
        in -> {
          String orgId = in.readUTF();
          long epoch = in.readLong();
          Map<String, Mode> modes = new TreeMap<>();
          for (int i = in.readInt(); i > 0; i--) {
            modes.put(in.readUTF(), Mode.fromCode(in.readUTF()));
          }
          return new ModeSnapshot(orgId, modes, epoch);
        });
  }

  // ------------------------------------------------------------------ plumbing

  private interface Writer {
    void write(DataOutputStream out) throws IOException;
  }

  private interface Reader<T> {
    T read(DataInputStream in) throws IOException;
  }

  private static byte[] write(Writer w) {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(buf)) {
      w.write(out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return buf.toByteArray();
  }

  /** Reads a result; an encoded error is rethrown as {@link IllegalArgumentException}. */
  private static <T> T read(byte[] bytes, Reader<T> r) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      return r.read(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Reads the status byte of a result and fails with the encoded message on an error. */
  static byte[] requireOk(byte[] bytes) {
    if (bytes.length == 0) {
      throw new IllegalStateException("empty reply");
    }
    if (bytes[0] == ERROR) {
      String message =
          read(java.util.Arrays.copyOfRange(bytes, 1, bytes.length), in -> in.readUTF());
      throw new IllegalArgumentException(message);
    }
    return java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
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
