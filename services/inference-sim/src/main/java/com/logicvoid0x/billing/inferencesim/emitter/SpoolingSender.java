package com.logicvoid0x.billing.inferencesim.emitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends keyed records to one Kafka topic, with a disk spool when Kafka fails (design: "If Kafka is
 * down it spools to disk; inference never waits on billing"). The host has one for usage and a
 * separate one for control totals.
 *
 * <ul>
 *   <li>{@link #send} only enqueues; one background thread delivers, so inference never waits.
 *   <li>While the spool holds anything, new records are appended behind it rather than overtaking
 *       it; the spool drains in append order.
 *   <li>A send that fails after every producer retry is appended to the spool when its failure is
 *       known, so it can land behind records sent after it. Order across a failure is not kept and
 *       is not needed: aggregation is by event time and deduplicated on requestId:meterCode.
 *   <li>After a failed send the sender counts Kafka as down: new records go straight to the spool,
 *       so none waits on metadata (max.block.ms) in memory. A drain that empties the spool clears
 *       it.
 *   <li>{@link #drain} probes with one record, then re-sends the rest in order, and advances the
 *       spool only past the contiguous prefix the broker acknowledged.
 * </ul>
 */
public final class SpoolingSender implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(SpoolingSender.class);

  private final UsageEmitter emitter;
  private final UsageSpool spool;
  private final Function<byte[], String> keyOf;
  private final int drainBatch;
  private final Duration ackWait;
  private final ExecutorService delivery =
      Executors.newSingleThreadExecutor(Thread.ofPlatform().name("spool-delivery").factory());
  private final ReentrantLock drainLock = new ReentrantLock();

  /** Set when a send fails; cleared when a drain empties the spool. */
  private volatile boolean down;

  /**
   * Creates the sender.
   *
   * @param emitter the Kafka emitter
   * @param spool the disk spool
   * @param keyOf the record key of a spooled payload (used when draining)
   * @param drainBatch most records re-sent per drain round
   * @param ackWait how long a drain waits for one record's acknowledgement
   */
  public SpoolingSender(
      UsageEmitter emitter,
      UsageSpool spool,
      Function<byte[], String> keyOf,
      int drainBatch,
      Duration ackWait) {
    this.keyOf = Objects.requireNonNull(keyOf, "keyOf");
    this.emitter = Objects.requireNonNull(emitter, "emitter");
    this.spool = Objects.requireNonNull(spool, "spool");
    this.ackWait = Objects.requireNonNull(ackWait, "ackWait");
    if (drainBatch < 1) {
      throw new IllegalArgumentException("drainBatch must be >= 1");
    }
    this.drainBatch = drainBatch;
  }

  /** Hands off one record; it is sent, or spooled if sending fails. Never blocks. */
  public void send(String key, byte[] encoded) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(encoded, "encoded");
    delivery.execute(() -> deliver(key, encoded));
  }

  private void deliver(String key, byte[] encoded) {
    // While Kafka is known to be down, go straight to disk: a send would block on metadata for up
    // to max.block.ms per record and hold every later record in memory behind it.
    if (down || !spool.isEmpty()) {
      spool.append(encoded);
      return;
    }
    var unused =
        emitter
            .send(key, encoded)
            .whenComplete((metadata, error) -> {
              if (error != null) {
                down = true;
                log.warn("send failed after every retry; spooling it: {}", error.toString());
                spoolLater(encoded);
              }
            });
  }

  /** Appends on the delivery thread; after shutdown, on the calling (producer) thread. */
  private void spoolLater(byte[] encoded) {
    try {
      delivery.execute(() -> spool.append(encoded));
    } catch (RejectedExecutionException e) {
      spool.append(encoded);
    }
  }

  /**
   * Re-sends spooled usage in order until the spool is empty or a send fails.
   *
   * @return records acknowledged and removed from the spool
   */
  public int drain() {
    if (!drainLock.tryLock()) {
      return 0; // another drain is running
    }
    try {
      int drained = 0;
      while (true) {
        List<UsageSpool.Entry> batch = spool.peek(drainBatch);
        if (batch.isEmpty()) {
          down = false;
          return drained;
        }
        // Probe with the first record before sending the rest: while Kafka is down, each send can
        // block on metadata for max.block.ms, so a whole batch would take batch x max.block.ms.
        UsageSpool.Entry first = batch.get(0);
        if (!acknowledged(emitter.send(keyOf.apply(first.payload()), first.payload()))) {
          return drained;
        }
        spool.commit(first.nextOffset(), 1);
        drained++;
        batch = batch.subList(1, batch.size());
        if (batch.isEmpty()) {
          continue;
        }
        List<CompletableFuture<RecordMetadata>> sends = new ArrayList<>(batch.size());
        for (UsageSpool.Entry e : batch) {
          sends.add(emitter.send(keyOf.apply(e.payload()), e.payload()));
        }
        int acked = 0;
        for (CompletableFuture<RecordMetadata> s : sends) {
          if (!acknowledged(s)) {
            break;
          }
          acked++;
        }
        if (acked > 0) {
          spool.commit(batch.get(acked - 1).nextOffset(), acked);
          drained += acked;
        }
        if (acked < batch.size()) {
          return drained; // Kafka is still failing; try again on the next round
        }
      }
    } finally {
      drainLock.unlock();
    }
  }

  private boolean acknowledged(CompletableFuture<RecordMetadata> send) {
    try {
      send.get(ackWait.toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (ExecutionException | TimeoutException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Records waiting in the spool (the spool-depth metric). */
  public long spoolDepth() {
    return spool.depth();
  }

  /** Waits until every record handed to {@link #send} so far was sent or spooled. */
  public void awaitHandoff(Duration timeout) throws InterruptedException, TimeoutException {
    CompletableFuture<Void> marker = new CompletableFuture<>();
    delivery.execute(() -> marker.complete(null));
    try {
      marker.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Stops taking records, finishes the queued hand-offs, then closes the emitter within its
   * delivery timeout. Sends that fail meanwhile are appended to the spool, which must stay open
   * until this returns. Never waits without a bound: a flush would wait forever on a dead broker.
   */
  @Override
  public void close() {
    delivery.shutdown();
    try {
      if (!delivery.awaitTermination(UsageEmitter.DELIVERY_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        log.error("usage delivery did not finish before shutdown");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    emitter.close();
  }
}
