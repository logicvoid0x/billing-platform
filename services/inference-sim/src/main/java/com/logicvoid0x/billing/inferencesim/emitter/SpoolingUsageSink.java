package com.logicvoid0x.billing.inferencesim.emitter;

import com.logicvoid0x.billing.domain.RequestUsage;
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
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The host's usage sink: Kafka first, the disk spool when Kafka fails (design: "If Kafka is down it
 * spools to disk; inference never waits on billing").
 *
 * <ul>
 *   <li>{@link #emit} only enqueues; one background thread delivers, so inference never waits.
 *   <li>While the spool holds anything, new usage is appended behind it rather than overtaking it;
 *       the spool drains in append order.
 *   <li>A send that fails after every producer retry is appended to the spool when its failure is
 *       known, so it can land behind usage produced after it. Order across a failure is not kept
 *       and is not needed: aggregation is by event time and deduplicated on requestId:meterCode.
 *   <li>{@link #drain} re-sends spooled records in order and advances the spool only past the
 *       contiguous prefix the broker acknowledged.
 * </ul>
 */
public final class SpoolingUsageSink implements UsageSink, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(SpoolingUsageSink.class);

  private final UsageEmitter emitter;
  private final UsageSpool spool;
  private final int drainBatch;
  private final Duration ackWait;
  private final ExecutorService delivery =
      Executors.newSingleThreadExecutor(Thread.ofPlatform().name("usage-delivery").factory());
  private final ReentrantLock drainLock = new ReentrantLock();

  /**
   * Creates the sink.
   *
   * @param emitter the Kafka emitter
   * @param spool the disk spool
   * @param drainBatch most records re-sent per drain round
   * @param ackWait how long a drain waits for one record's acknowledgement
   */
  public SpoolingUsageSink(
      UsageEmitter emitter, UsageSpool spool, int drainBatch, Duration ackWait) {
    this.emitter = Objects.requireNonNull(emitter, "emitter");
    this.spool = Objects.requireNonNull(spool, "spool");
    this.ackWait = Objects.requireNonNull(ackWait, "ackWait");
    if (drainBatch < 1) {
      throw new IllegalArgumentException("drainBatch must be >= 1");
    }
    this.drainBatch = drainBatch;
  }

  @Override
  public void emit(RequestUsage usage) {
    byte[] encoded = UsageEventCodec.encode(usage);
    String key = usage.orgId();
    delivery.execute(() -> deliver(key, encoded));
  }

  private void deliver(String key, byte[] encoded) {
    if (!spool.isEmpty()) {
      spool.append(encoded);
      return;
    }
    var unused =
        emitter
            .send(key, encoded)
            .whenComplete((metadata, error) -> {
              if (error != null) {
                log.warn("usage send failed after every retry; spooling it: {}", error.toString());
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
          return drained;
        }
        List<CompletableFuture<RecordMetadata>> sends = new ArrayList<>(batch.size());
        for (UsageSpool.Entry e : batch) {
          sends.add(emitter.send(UsageEventCodec.decode(e.payload()).orgId(), e.payload()));
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

  /** Waits until every usage handed to {@link #emit} so far was sent or spooled. */
  public void awaitHandoff(Duration timeout) throws InterruptedException, TimeoutException {
    CompletableFuture<Void> marker = new CompletableFuture<>();
    delivery.execute(() -> marker.complete(null));
    try {
      marker.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

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
    emitter.flush();
  }
}
