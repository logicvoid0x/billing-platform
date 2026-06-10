package com.logicvoid0x.billing.inferencesim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InferenceSimulatorTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:15:00Z");
  private final InferenceSimulator sim =
      new InferenceSimulator("host-a", Clock.fixed(NOW, ZoneOffset.UTC));

  private static SimulatedRequest request(long maxOutput, long seed) {
    return new SimulatedRequest(
        "req_1", "org_42", "ws_7", "key_3", "model-x", "standard", 1_200, 300, 0, maxOutput, seed);
  }

  @Test
  void streamsChunksAndCountsByMeter() {
    List<Chunk> chunks = new ArrayList<>();
    InferenceResult r = sim.run(request(5_000, 7), 512, new StopSignal(), chunks::add);

    assertThat(r.outcome()).isEqualTo(InferenceResult.Outcome.COMPLETED);
    long streamed = chunks.stream().mapToLong(Chunk::tokens).sum();
    assertThat(streamed).isEqualTo(r.outputTokens()).isBetween(1L, 5_000L);
    assertThat(chunks).allSatisfy(c -> assertThat(c.tokens()).isBetween(1, 512));
    assertThat(chunks).extracting(Chunk::index).containsExactlyElementsOf(indexes(chunks.size()));
    assertThat(r.counts())
        .containsEntry(MeterCode.INPUT_TOKENS, 1_200L)
        .containsEntry(MeterCode.CACHE_READ_TOKENS, 300L)
        .containsEntry(MeterCode.OUTPUT_TOKENS, streamed)
        .doesNotContainKey(MeterCode.CACHE_WRITE_TOKENS);
  }

  @Test
  void sameSeedGivesTheSameStream() {
    List<Chunk> a = new ArrayList<>();
    List<Chunk> b = new ArrayList<>();
    sim.run(request(10_000, 42), 256, new StopSignal(), a::add);
    sim.run(request(10_000, 42), 256, new StopSignal(), b::add);
    assertThat(a).isEqualTo(b);
  }

  @Test
  void stopSignalEndsTheStreamAtAChunkBoundaryWithItsReason() {
    StopSignal stop = new StopSignal();
    List<Chunk> chunks = new ArrayList<>();
    InferenceResult r =
        sim.run(
            request(100_000, 3),
            100,
            stop,
            c -> {
              chunks.add(c);
              if (chunks.size() == 3) {
                stop.stop("budget_exhausted");
              }
            });
    assertThat(r.outcome()).isEqualTo(InferenceResult.Outcome.STOPPED);
    assertThat(r.stopReason()).contains("budget_exhausted");
    assertThat(chunks).hasSize(3);
    assertThat(r.outputTokens()).isEqualTo(300);
    assertThat(r.counts()).containsEntry(MeterCode.OUTPUT_TOKENS, 300L);
  }

  @Test
  void usageIsEmittedOnlyWhenOutputWasDelivered() {
    StopSignal stop = new StopSignal();
    stop.stop("client_cancelled");
    InferenceResult none = sim.run(request(1_000, 1), 100, stop, c -> {});
    assertThat(none.outputTokens()).isZero();
    assertThat(none.usageIfDelivered()).isEmpty();

    InferenceResult done = sim.run(request(1_000, 1), 100, new StopSignal(), c -> {});
    Optional<RequestUsage> usage = done.usageIfDelivered();
    assertThat(usage).isPresent();
    RequestUsage u = usage.get();
    assertThat(u.requestId()).isEqualTo("req_1");
    assertThat(u.hostId()).isEqualTo("host-a");
    assertThat(u.eventTime()).isEqualTo(NOW);
    assertThat(u.quantities()).containsEntry(MeterCode.OUTPUT_TOKENS, done.outputTokens());
  }

  @Test
  void stopSignalKeepsTheFirstReason() {
    StopSignal stop = new StopSignal();
    assertThat(stop.reason()).isEmpty();
    stop.stop("budget_exhausted");
    stop.stop("client_cancelled");
    assertThat(stop.isStopped()).isTrue();
    assertThat(stop.reason()).contains("budget_exhausted");
  }

  @Test
  void requestsAreValidated() {
    assertThatThrownBy(() -> request(0, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SimulatedRequest("req", "org", "ws", "key", "m", "standard", -1, 0, 0, 10, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sim.run(request(10, 1), 0, new StopSignal(), c -> {}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static List<Integer> indexes(int n) {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(i);
    }
    return out;
  }
}
