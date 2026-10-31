package com.logicvoid0x.billing.usageaggregator;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.MeteredLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class DedupeLinesTest {
  @RegisterExtension
  static final MiniClusterExtension FLINK =
      new MiniClusterExtension(
          new MiniClusterResourceConfiguration.Builder()
              .setNumberTaskManagers(1)
              .setNumberSlotsPerTaskManager(4)
              .build());

  private static final Instant T = Instant.parse("2026-11-01T10:15:00Z");

  private static MeteredLine line(String request, MeterCode meter, long quantity) {
    return new MeteredLine(
        request, "org_1", "ws_1", "key_1", "sku_" + meter.code(), meter, quantity, T);
  }

  private static KeyedOneInputStreamOperatorTestHarness<String, MeteredLine, MeteredLine> harness()
      throws Exception {
    var h =
        new KeyedOneInputStreamOperatorTestHarness<>(
            new KeyedProcessOperator<>(new DedupeLines()), DedupeLines::key, Types.STRING);
    h.open();
    return h;
  }

  private static List<String> outputs(
      KeyedOneInputStreamOperatorTestHarness<String, MeteredLine, MeteredLine> h) {
    List<String> out = new ArrayList<>();
    h.extractOutputValues().forEach(l -> out.add(DedupeLines.key(l) + "=" + l.quantity()));
    return out;
  }

  @Test
  void theFirstLinePerRequestAndMeterWinsAndConflictsAreDropped() throws Exception {
    var h = harness();
    try {
      h.processElement(line("req_1", MeterCode.OUTPUT_TOKENS, 10), 0);
      h.processElement(line("req_1", MeterCode.INPUT_TOKENS, 99), 0);
      h.processElement(line("req_1", MeterCode.OUTPUT_TOKENS, 10), 0); // redelivery
      h.processElement(line("req_1", MeterCode.OUTPUT_TOKENS, 11), 0); // conflicting duplicate
      h.processElement(line("req_2", MeterCode.OUTPUT_TOKENS, 10), 0);
      assertThat(outputs(h))
          .containsExactly(
              "req_1:output_tokens=10", "req_1:input_tokens=99", "req_2:output_tokens=10");
    } finally {
      h.close();
    }
  }

  @Test
  void keysAreForgottenSevenDaysAfterTheyWereWritten() throws Exception {
    var h = harness();
    try {
      h.setStateTtlProcessingTime(0);
      h.processElement(line("req_1", MeterCode.OUTPUT_TOKENS, 10), 0);
      h.setStateTtlProcessingTime(DedupeLines.TTL.toMillis() - 1);
      h.processElement(line("req_1", MeterCode.OUTPUT_TOKENS, 10), 0);
      assertThat(outputs(h)).as("still remembered just before 7 days").hasSize(1);

      h.setStateTtlProcessingTime(DedupeLines.TTL.toMillis() + 1);
      h.processElement(line("req_1", MeterCode.OUTPUT_TOKENS, 10), 0);
      assertThat(outputs(h)).as("expired after 7 days").hasSize(2);
    } finally {
      h.close();
    }
  }

  @Test
  void duplicatesSpreadAcrossParallelInputsPassExactlyOnce() throws Exception {
    List<MeteredLine> input = new ArrayList<>();
    for (int copy = 0; copy < 3; copy++) {
      for (int r = 0; r < 50; r++) {
        input.add(line("req_" + r, MeterCode.OUTPUT_TOKENS, r + 1));
        input.add(line("req_" + r, MeterCode.INPUT_TOKENS, 1000 + r));
      }
    }
    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setParallelism(4);
    List<String> out = new ArrayList<>();
    UsageAggregatorJob.dedupe(env.fromData(input).rebalance())
        .map(l -> DedupeLines.key(l) + "=" + l.quantity())
        .returns(Types.STRING)
        .executeAndCollect()
        .forEachRemaining(out::add);
    assertThat(out).hasSize(100).doesNotHaveDuplicates();
    assertThat(out).contains("req_7:output_tokens=8", "req_7:input_tokens=1007");
  }
}
