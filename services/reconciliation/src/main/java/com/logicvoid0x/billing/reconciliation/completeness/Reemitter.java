package com.logicvoid0x.billing.reconciliation.completeness;

import com.logicvoid0x.billing.contracts.codec.UsageEventCodec;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Sends missing usage back to {@code usage.raw}, keyed by org, under its original request ID, with
 * the same explicit delivery settings as the inference hosts. Waits for every acknowledgement, so a
 * completeness run is only marked done once its re-emits are durable.
 */
public final class Reemitter {
  /** The raw usage topic. */
  public static final String TOPIC = "usage.raw";

  private final Producer<String, byte[]> producer;

  /** Creates the re-emitter over a producer configured with {@link #producerConfig}. */
  public Reemitter(Producer<String, byte[]> producer) {
    this.producer = Objects.requireNonNull(producer, "producer");
  }

  /** acks=all, idempotence, unlimited retries within 120 s. */
  public static Map<String, Object> producerConfig(String bootstrapServers) {
    return Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
        bootstrapServers,
        ProducerConfig.CLIENT_ID_CONFIG,
        "reconciliation-reemit",
        ProducerConfig.ACKS_CONFIG,
        "all",
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
        true,
        ProducerConfig.RETRIES_CONFIG,
        Integer.MAX_VALUE,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
        120_000);
  }

  /**
   * Re-emits and waits for acknowledgement.
   *
   * @return records acknowledged
   */
  public int reemit(List<RequestUsage> missing) throws InterruptedException, ExecutionException {
    List<java.util.concurrent.Future<?>> sends = new java.util.ArrayList<>();
    for (RequestUsage u : missing) {
      sends.add(producer.send(new ProducerRecord<>(TOPIC, u.orgId(), UsageEventCodec.encode(u))));
    }
    for (java.util.concurrent.Future<?> f : sends) {
      f.get();
    }
    return sends.size();
  }
}
