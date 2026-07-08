package com.logicvoid0x.billing.inferencesim.emitter;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.v1.ControlTotals;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Publishes this host's control totals to {@code usage.control} (key = host ID) through its own
 * producer and spool, separate from usage, so a usage outage cannot also hide the totals.
 *
 * <p>Each publish sends one message per event hour that had usage since the previous publish: the
 * delta of requests and meter sums. Every message gets the host's next sequence number. The
 * sequence is persisted before the message is handed off, so it keeps increasing across restarts,
 * and a spooled resend carries the same bytes and sequence. Reconciliation sums the deltas per
 * (host, hour), deduplicates on (host, sequence), and reads a sequence gap as a lost message.
 */
public final class ControlTotalsPublisher {
  /** The control-totals topic. */
  public static final String TOPIC = "usage.control";

  private final String hostId;
  private final ControlTotalsRecorder recorder;
  private final SpoolingSender sender;
  private final Clock clock;
  private final Path sequenceFile;
  private final ReentrantLock lock = new ReentrantLock();
  private long lastSequence;

  /**
   * Creates the publisher.
   *
   * @param hostId this host
   * @param recorder the counts to publish
   * @param sender a sender to {@link #TOPIC} with its own spool
   * @param clock stamps {@code emitted_at}
   * @param stateDir where the last used sequence number is kept
   */
  public ControlTotalsPublisher(
      String hostId,
      ControlTotalsRecorder recorder,
      SpoolingSender sender,
      Clock clock,
      Path stateDir) {
    this.hostId = Objects.requireNonNull(hostId, "hostId");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.sender = Objects.requireNonNull(sender, "sender");
    this.clock = Objects.requireNonNull(clock, "clock");
    try {
      Files.createDirectories(stateDir);
      this.sequenceFile = stateDir.resolve("control.sequence");
      this.lastSequence =
          Files.exists(sequenceFile)
              ? Long.parseLong(Files.readString(sequenceFile, StandardCharsets.US_ASCII).trim())
              : 0;
    } catch (IOException e) {
      throw new UncheckedIOException("reading the control-totals sequence failed", e);
    }
  }

  /** Publishes the deltas since the last publish; returns the messages handed off. */
  public List<ControlTotals> publish() {
    lock.lock();
    try {
      List<ControlTotals> sent = new ArrayList<>();
      Instant now = clock.instant();
      for (Map.Entry<Instant, ControlTotalsRecorder.Delta> e : recorder.takeDeltas().entrySet()) {
        long sequence = lastSequence + 1;
        persistSequence(sequence);
        lastSequence = sequence;
        ControlTotals.Builder b =
            ControlTotals.newBuilder()
                .setHostId(hostId)
                .setEventHour(timestamp(e.getKey()))
                .setSequence(sequence)
                .setRequestCount(e.getValue().requestCount())
                .setEmittedAt(timestamp(now));
        e.getValue().quantityByMeter().forEach((m, q) -> b.putQuantityByMeter(m.code(), q));
        ControlTotals message = b.build();
        sender.send(hostId, message.toByteArray());
        sent.add(message);
      }
      return sent;
    } finally {
      lock.unlock();
    }
  }

  /** The record key of a spooled control message: its host ID. */
  public static String keyOf(byte[] payload) {
    try {
      return ControlTotals.parseFrom(payload).getHostId();
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("not a ControlTotals message", e);
    }
  }

  private void persistSequence(long sequence) {
    Path tmp = sequenceFile.resolveSibling("control.sequence.tmp");
    try {
      Files.writeString(tmp, Long.toString(sequence), StandardCharsets.US_ASCII);
      Files.move(
          tmp, sequenceFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("persisting the control-totals sequence failed", e);
    }
  }

  private static Timestamp timestamp(Instant t) {
    return Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
  }
}
