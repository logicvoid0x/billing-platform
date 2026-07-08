package com.logicvoid0x.billing.inferencesim.emitter;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.contracts.v1.ControlTotals;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlTotalsTest {
  private static final Instant H10 = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant H11 = Instant.parse("2026-11-01T11:00:00Z");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-01T11:00:30Z"), ZoneOffset.UTC);

  @TempDir Path spoolDir;
  @TempDir Path stateDir;

  private static RequestUsage usage(String id, Instant at, long in, long out) {
    return new RequestUsage(
        id,
        "org_1",
        "ws_1",
        "key_1",
        "model-x",
        "standard",
        "host-a",
        at,
        Map.of(MeterCode.INPUT_TOKENS, in, MeterCode.OUTPUT_TOKENS, out));
  }

  @Test
  void theRecorderSumsPerEventHourAndResetsOnTake() {
    ControlTotalsRecorder r = new ControlTotalsRecorder();
    r.record(usage("a", H10.plusSeconds(10), 100, 5));
    r.record(usage("b", H10.plusSeconds(3599), 50, 7));
    r.record(usage("c", H11.plusSeconds(1), 1, 1));

    var deltas = r.takeDeltas();
    assertThat(deltas).containsOnlyKeys(H10, H11);
    assertThat(deltas.get(H10).requestCount()).isEqualTo(2);
    assertThat(deltas.get(H10).quantityByMeter())
        .containsExactly(
            Map.entry(MeterCode.INPUT_TOKENS, 150L), Map.entry(MeterCode.OUTPUT_TOKENS, 12L));
    assertThat(r.takeDeltas()).as("taken").isEmpty();
  }

  @Test
  void eachPublishSendsOneDeltaPerHourWithAnIncreasingPersistedSequence() throws Exception {
    MockProducer<String, byte[]> producer =
        new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
    ControlTotalsRecorder recorder = new ControlTotalsRecorder();
    UsageSpool spool = UsageSpool.open(spoolDir);
    try (SpoolingSender sender =
        new SpoolingSender(
            new UsageEmitter(producer, ControlTotalsPublisher.TOPIC),
            spool,
            ControlTotalsPublisher::keyOf,
            10,
            Duration.ofSeconds(5))) {
      ControlTotalsPublisher publisher =
          new ControlTotalsPublisher("host-a", recorder, sender, CLOCK, stateDir);

      assertThat(publisher.publish()).as("nothing emitted yet").isEmpty();

      recorder.record(usage("a", H10.plusSeconds(10), 100, 5));
      recorder.record(usage("b", H11.plusSeconds(10), 3, 4));
      List<ControlTotals> first = publisher.publish();
      assertThat(first).extracting(ControlTotals::getSequence).containsExactly(1L, 2L);
      assertThat(first.get(0).getRequestCount()).isEqualTo(1);
      assertThat(first.get(0).getQuantityByMeterMap())
          .containsEntry("input_tokens", 100L)
          .containsEntry("output_tokens", 5L);
      assertThat(first.get(0).getEventHour().getSeconds()).isEqualTo(H10.getEpochSecond());

      recorder.record(usage("c", H11.plusSeconds(20), 1, 1));
      assertThat(publisher.publish()).extracting(ControlTotals::getSequence).containsExactly(3L);

      // A restarted host continues the sequence.
      ControlTotalsPublisher restarted =
          new ControlTotalsPublisher("host-a", recorder, sender, CLOCK, stateDir);
      recorder.record(usage("d", H11.plusSeconds(30), 1, 1));
      assertThat(restarted.publish()).extracting(ControlTotals::getSequence).containsExactly(4L);

      sender.awaitHandoff(Duration.ofSeconds(5));
    } finally {
      spool.close();
    }

    List<ProducerRecord<String, byte[]>> sent = producer.history();
    assertThat(sent).hasSize(4);
    assertThat(sent).allSatisfy(r -> assertThat(r.topic()).isEqualTo("usage.control"));
    assertThat(sent).allSatisfy(r -> assertThat(r.key()).isEqualTo("host-a"));
    assertThat(sent)
        .extracting(r -> ControlTotals.parseFrom(r.value()).getSequence())
        .containsExactly(1L, 2L, 3L, 4L);
  }

  @Test
  void theHostSinkCountsUsageAndSendsItKeyedByOrg() throws Exception {
    MockProducer<String, byte[]> producer =
        new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
    ControlTotalsRecorder recorder = new ControlTotalsRecorder();
    UsageSpool spool = UsageSpool.open(spoolDir);
    try (SpoolingSender sender =
        new SpoolingSender(
            new UsageEmitter(producer, UsageEmitter.TOPIC),
            spool,
            HostUsageSink::keyOf,
            10,
            Duration.ofSeconds(5))) {
      new HostUsageSink(sender, recorder).emit(usage("a", H10, 10, 2));
      sender.awaitHandoff(Duration.ofSeconds(5));
    } finally {
      spool.close();
    }
    assertThat(producer.history())
        .singleElement()
        .satisfies(r -> assertThat(r.key()).isEqualTo("org_1"));
    assertThat(recorder.takeDeltas().get(H10).requestCount()).isEqualTo(1);
  }
}
