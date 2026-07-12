package com.logicvoid0x.billing.usageaggregator;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.codec.UsageEventCodec;
import com.logicvoid0x.billing.contracts.v1.RequestUsageEvent;
import com.logicvoid0x.billing.contracts.v1.UsageDeadLetter;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.MeteredLine;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ResolveUsageTest {
  @RegisterExtension
  static final MiniClusterExtension FLINK =
      new MiniClusterExtension(
          new MiniClusterResourceConfiguration.Builder()
              .setNumberTaskManagers(1)
              .setNumberSlotsPerTaskManager(2)
              .build());

  /** The golden catalog, rebuilt on each task (catalog records are not serializable). */
  static final class GoldenRatingSource implements RatingSource {
    private static final long serialVersionUID = 1L;
    private transient RatingLibrary library;

    @Override
    public void open() {
      library = new CatalogRatingLibrary(GoldenCases.catalog(), "test");
    }

    @Override
    public RatingLibrary at(Instant eventTime) {
      return library;
    }
  }

  private static RequestUsage usage(String id, String model, Map<MeterCode, Long> q) {
    return new RequestUsage(
        id, "org_acme", "ws_1", "key_1", model, "standard", "host-a", GoldenCases.HOUR, q);
  }

  private static RawUsage raw(byte[] payload, long offset) {
    return new RawUsage(payload, "usage.raw", 0, offset);
  }

  @Test
  void validUsageResolvesAndEverythingUnbillableGoesToTheDeadLetterQueue() throws Exception {
    byte[] invalid =
        RequestUsageEvent.newBuilder()
            .setRequestId("req_bad")
            .setOrgId("") // blank: rejected by RequestUsage
            .setWorkspaceId("ws_1")
            .setApiKeyId("key_1")
            .setModel("model-x")
            .setServiceTier("standard")
            .setHostId("host-a")
            .setEventTime(Timestamp.newBuilder().setSeconds(GoldenCases.HOUR.getEpochSecond()))
            .putQuantities("output_tokens", 5)
            .build()
            .toByteArray();
    List<RawUsage> input =
        List.of(
            raw(
                UsageEventCodec.encode(
                    usage("req_ok", "model-x", Map.of(MeterCode.OUTPUT_TOKENS, 40_000_000L))),
                1),
            raw(new byte[] {(byte) 0xff, 0x01, 0x02}, 2),
            raw(invalid, 3),
            raw(
                UsageEventCodec.encode(
                    usage("req_unknown", "model-new", Map.of(MeterCode.OUTPUT_TOKENS, 7L))),
                4),
            raw(
                UsageEventCodec.encode(
                    usage(
                        "req_zero",
                        "model-x",
                        Map.of(MeterCode.OUTPUT_TOKENS, 3L, MeterCode.INPUT_TOKENS, 0L))),
                5));

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setParallelism(2);
    DataStream<RawUsage> raw = env.fromData(input);
    SingleOutputStreamOperator<MeteredLine> lines =
        UsageAggregatorJob.resolve(raw, new GoldenRatingSource());
    DataStream<String> both =
        lines
            .map(l ->
                "line " + l.requestId() + " " + l.skuId() + " " + l.meter() + " " + l.quantity())
            .returns(Types.STRING)
            .union(
                lines
                    .getSideOutput(ResolveUsage.DEAD_LETTERS)
                    .map(b -> {
                      UsageDeadLetter d = UsageDeadLetter.parseFrom(b);
                      return "dlq "
                          + d.getSourceTopic()
                          + "@"
                          + d.getSourceOffset()
                          + " "
                          + d.getReason().substring(0, d.getReason().indexOf(':'))
                          + " "
                          + d.getPayload().size();
                    })
                    .returns(Types.STRING));

    List<String> out = new ArrayList<>();
    both.executeAndCollect().forEachRemaining(out::add);

    assertThat(out)
        .containsExactlyInAnyOrder(
            "line req_ok sku_out output_tokens 40000000",
            "line req_zero sku_out output_tokens 3", // zero meters produce no line
            "dlq usage.raw@2 malformed 3",
            "dlq usage.raw@3 invalid " + invalid.length,
            "dlq usage.raw@4 unknown_sku " + input.get(3).payload().length);
  }
}
