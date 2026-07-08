package com.logicvoid0x.billing.inferencesim.emitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

/**
 * Append-only disk spool of encoded events, drained in the order they were appended.
 *
 * <p>Layout in its directory: {@code spool.log} holds records of {@code [int length][bytes][int
 * crc32c]}; {@code spool.offset} holds the byte offset of the first record not yet acknowledged.
 * Every append is forced to disk before it returns. The offset only moves forward, after the
 * records before it were acknowledged by the broker, and is replaced atomically. Once everything is
 * acknowledged the log is truncated, then the offset is reset (a crash in between leaves an offset
 * past the end of the log, which recovery reads as "all drained").
 *
 * <p>On open, records after the offset are counted; a torn record at the tail (a crash during an
 * append) is cut off. A drain can re-send records whose acknowledgement was lost; downstream dedupe
 * on {@code requestId:meterCode} absorbs those duplicates (at-least-once).
 */
public final class UsageSpool implements AutoCloseable {
  /** One spooled record: its payload and where it starts and ends in the log. */
  public static final class Entry {
    private final long offset;
    private final long nextOffset;
    private final byte[] payload;

    Entry(long offset, long nextOffset, byte[] payload) {
      this.offset = offset;
      this.nextOffset = nextOffset;
      this.payload = payload.clone();
    }

    /** Byte offset of the record. */
    public long offset() {
      return offset;
    }

    /** Byte offset just past the record; pass it to {@link UsageSpool#commit}. */
    public long nextOffset() {
      return nextOffset;
    }

    /** A copy of the record's bytes. */
    public byte[] payload() {
      return payload.clone();
    }
  }

  private static final int MAX_RECORD = 16 * 1024 * 1024;

  private final Path offsetFile;
  private final Path offsetTmp;
  private final FileChannel log;
  private final ReentrantLock lock = new ReentrantLock();
  private long committed;
  private long depth;

  private UsageSpool(Path dir) throws IOException {
    Files.createDirectories(dir);
    this.offsetFile = dir.resolve("spool.offset");
    this.offsetTmp = dir.resolve("spool.offset.tmp");
    this.log =
        FileChannel.open(
            dir.resolve("spool.log"),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE);
    recover();
  }

  /** Opens (or creates) the spool in {@code dir} and recovers its state. */
  public static UsageSpool open(Path dir) {
    try {
      return new UsageSpool(dir);
    } catch (IOException e) {
      throw new UncheckedIOException("opening the usage spool in " + dir + " failed", e);
    }
  }

  /** Appends one record and forces it to disk. */
  public void append(byte[] payload) {
    if (payload.length > MAX_RECORD) {
      throw new IllegalArgumentException("record too large: " + payload.length);
    }
    ByteBuffer b = ByteBuffer.allocate(payload.length + 8);
    b.putInt(payload.length).put(payload).putInt(crc(payload)).flip();
    lock.lock();
    try {
      long end = log.size();
      while (b.hasRemaining()) {
        end += log.write(b, end);
      }
      log.force(false);
      depth++;
    } catch (IOException e) {
      throw new UncheckedIOException("appending to the usage spool failed", e);
    } finally {
      lock.unlock();
    }
  }

  /** Up to {@code max} records from the first unacknowledged one, in append order. */
  public List<Entry> peek(int max) {
    lock.lock();
    try {
      List<Entry> out = new ArrayList<>();
      long pos = committed;
      long size = log.size();
      while (out.size() < max && pos < size) {
        Entry e = read(pos, size);
        if (e == null) {
          break;
        }
        out.add(e);
        pos = e.nextOffset();
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException("reading the usage spool failed", e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Marks every record before {@code nextOffset} as acknowledged.
   *
   * @param nextOffset an {@link Entry#nextOffset()} from {@link #peek}
   * @param records how many records that acknowledges
   */
  public void commit(long nextOffset, int records) {
    lock.lock();
    try {
      if (nextOffset < committed || nextOffset > log.size() || records < 0 || records > depth) {
        throw new IllegalArgumentException("commit outside the spool: " + nextOffset);
      }
      writeOffset(nextOffset);
      committed = nextOffset;
      depth -= records;
      if (committed == log.size()) {
        // Everything is acknowledged: empty the log, then the offset (see the class comment).
        log.truncate(0);
        log.force(true);
        writeOffset(0);
        committed = 0;
        depth = 0;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("committing the usage spool offset failed", e);
    } finally {
      lock.unlock();
    }
  }

  /** Records appended and not yet acknowledged. */
  public long depth() {
    lock.lock();
    try {
      return depth;
    } finally {
      lock.unlock();
    }
  }

  /** True when nothing is waiting. */
  public boolean isEmpty() {
    return depth() == 0;
  }

  @Override
  public void close() {
    lock.lock();
    try {
      log.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      lock.unlock();
    }
  }

  private void recover() throws IOException {
    long size = log.size();
    long offset = Files.exists(offsetFile) ? readOffset() : 0;
    if (offset > size) {
      offset = 0; // the log was truncated after a full drain, before the offset was reset
      writeOffset(0);
    }
    long pos = offset;
    long count = 0;
    while (pos < size) {
      Entry e = read(pos, size);
      if (e == null) {
        log.truncate(pos); // torn tail from a crash during an append
        log.force(true);
        break;
      }
      pos = e.nextOffset();
      count++;
    }
    committed = offset;
    depth = count;
  }

  /** The record at {@code pos}, or null if it is incomplete or corrupt. */
  private Entry read(long pos, long size) throws IOException {
    if (size - pos < 8) {
      return null;
    }
    ByteBuffer len = ByteBuffer.allocate(4);
    readFully(len, pos);
    int length = len.flip().getInt();
    if (length < 0 || length > MAX_RECORD || size - pos - 8 < length) {
      return null;
    }
    ByteBuffer body = ByteBuffer.allocate(length + 4);
    readFully(body, pos + 4);
    body.flip();
    byte[] payload = new byte[length];
    body.get(payload);
    if (body.getInt() != crc(payload)) {
      return null;
    }
    return new Entry(pos, pos + 8 + length, payload);
  }

  private void readFully(ByteBuffer b, long pos) throws IOException {
    long p = pos;
    while (b.hasRemaining()) {
      int n = log.read(b, p);
      if (n < 0) {
        throw new IOException("unexpected end of spool at " + p);
      }
      p += n;
    }
  }

  private long readOffset() throws IOException {
    return Long.parseLong(Files.readString(offsetFile, StandardCharsets.US_ASCII).trim());
  }

  private void writeOffset(long offset) throws IOException {
    try (FileChannel tmp =
        FileChannel.open(
            offsetTmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
      tmp.write(ByteBuffer.wrap(Long.toString(offset).getBytes(StandardCharsets.US_ASCII)));
      tmp.force(true);
    }
    Files.move(
        offsetTmp, offsetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static int crc(byte[] payload) {
    CRC32C c = new CRC32C();
    c.update(payload);
    return (int) c.getValue();
  }
}
