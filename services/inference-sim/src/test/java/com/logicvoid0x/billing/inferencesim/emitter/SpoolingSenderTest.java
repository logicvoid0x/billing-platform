package com.logicvoid0x.billing.inferencesim.emitter;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.RequestUsage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpoolingSenderTest {
  @TempDir Path dir;

  /** A producer whose broker can be switched off: sends fail at once while it is down. */
  private static final class SwitchableProducer extends MockProducer<String, byte[]> {
    final AtomicBoolean down = new AtomicBoolean();
    int okBeforeDown = Integer.MAX_VALUE; // goes down after this many more successful sends
    final List<String> acked = new ArrayList<>();

    SwitchableProducer() {
      super(true, null, new StringSerializer(), new ByteArraySerializer());
    }

    @Override
    public synchronized java.util.concurrent.Future<RecordMetadata> send(
        ProducerRecord<String, byte[]> record, org.apache.kafka.clients.producer.Callback cb) {
      if (okBeforeDown-- <= 0) {
        down.set(true);
      }
      if (down.get()) {
        cb.onCompletion(null, new org.apache.kafka.common.errors.TimeoutException("broker down"));
        return CompletableFuture.failedFuture(new IllegalStateException("down"));
      }
      acked.add(UsageEventCodec.decode(record.value()).requestId());
      return super.send(record, cb);
    }
  }

  private static void send(SpoolingSender sender, RequestUsage u) {
    sender.send(u.orgId(), UsageEventCodec.encode(u));
  }

  private static RequestUsage usage(int i) {
    return UsageEmitterTest.usage("req_" + i, "org_" + (i % 3));
  }

  @Test
  void usageSpoolsWhileKafkaIsDownAndDrainsInOrderWhenItReturns() throws Exception {
    SwitchableProducer producer = new SwitchableProducer();
    UsageSpool spool = UsageSpool.open(dir);
    try (SpoolingSender sink =
        new SpoolingSender(
            new UsageEmitter(producer, "usage.raw"),
            spool,
            HostUsageSink::keyOf,
            2,
            Duration.ofSeconds(5))) {
      send(sink, usage(0));
      sink.awaitHandoff(Duration.ofSeconds(5));
      assertThat(producer.acked).containsExactly("req_0");

      producer.down.set(true);
      for (int i = 1; i <= 5; i++) {
        send(sink, usage(i));
      }
      sink.awaitHandoff(Duration.ofSeconds(5));
      sink.awaitHandoff(Duration.ofSeconds(5)); // failure callbacks hand the append back once more
      assertThat(sink.spoolDepth()).isEqualTo(5);
      assertThat(sink.drain()).as("still down").isZero();

      producer.down.set(false);
      send(sink, usage(6)); // arrives while the spool is non-empty: queued behind it
      sink.awaitHandoff(Duration.ofSeconds(5));
      assertThat(sink.spoolDepth()).isEqualTo(6);

      assertThat(sink.drain()).isEqualTo(6);
      assertThat(sink.spoolDepth()).isZero();
      // req_1..5 failed while Kafka was down and were spooled in the order their failures were
      // seen; req_6 was appended behind them, so it drains last.
      assertThat(producer.acked).hasSize(7).startsWith("req_0").endsWith("req_6");
      assertThat(producer.acked.subList(1, 6))
          .containsExactlyInAnyOrder("req_1", "req_2", "req_3", "req_4", "req_5");

      send(sink, usage(7)); // spool empty again: straight to Kafka
      sink.awaitHandoff(Duration.ofSeconds(5));
      assertThat(producer.acked).endsWith("req_7");
    } finally {
      spool.close();
    }
  }

  @Test
  void aDrainThatFailsHalfwayKeepsTheUnacknowledgedTailInOrder() throws Exception {
    SwitchableProducer producer = new SwitchableProducer();
    UsageSpool spool = UsageSpool.open(dir);
    for (int i = 0; i < 4; i++) {
      spool.append(UsageEventCodec.encode(usage(i)));
    }
    try (SpoolingSender sink =
        new SpoolingSender(
            new UsageEmitter(producer, "usage.raw"),
            spool,
            HostUsageSink::keyOf,
            10,
            Duration.ofSeconds(5))) {
      producer.okBeforeDown = 2;
      assertThat(sink.drain()).isEqualTo(2);
      assertThat(sink.spoolDepth()).isEqualTo(2);

      producer.down.set(false);
      producer.okBeforeDown = Integer.MAX_VALUE;
      assertThat(sink.drain()).isEqualTo(2);
      assertThat(producer.acked).containsExactly("req_0", "req_1", "req_2", "req_3");
    } finally {
      spool.close();
    }
  }
}
