package com.logicvoid0x.billing.inferencesim;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/** Simulated inference: streams deterministic output in chunks and honours a stop signal. */
public final class InferenceSimulator {
  private final String hostId;
  private final Clock clock;
  private final Duration chunkDelay;

  /** A simulator that streams as fast as the sink accepts. */
  public InferenceSimulator(String hostId, Clock clock) {
    this(hostId, clock, Duration.ZERO);
  }

  /**
   * Creates the simulator.
   *
   * @param hostId reported on usage events
   * @param clock event time of the usage
   * @param chunkDelay pause after each chunk, to simulate generation time
   */
  public InferenceSimulator(String hostId, Clock clock, Duration chunkDelay) {
    this.hostId = Objects.requireNonNull(hostId, "hostId");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.chunkDelay = Objects.requireNonNull(chunkDelay, "chunkDelay");
  }

  /**
   * Runs one request: emits chunks of up to {@code chunkTokens} to {@code sink}, checking {@code
   * stop} before each chunk.
   */
  public InferenceResult run(
      SimulatedRequest request, int chunkTokens, StopSignal stop, Consumer<Chunk> sink) {
    if (chunkTokens <= 0) {
      throw new IllegalArgumentException("chunkTokens must be > 0");
    }
    SplittableRandom random = new SplittableRandom(request.seed());
    long target = random.nextLong(1, request.maxOutputTokens() + 1);
    long streamed = 0;
    int index = 0;
    while (streamed < target) {
      if (stop.isStopped()) {
        return result(request, InferenceResult.Outcome.STOPPED, stop, streamed);
      }
      int tokens = (int) Math.min(chunkTokens, target - streamed);
      sink.accept(new Chunk(index, tokens, text(random, tokens)));
      streamed += tokens;
      index++;
      pause(stop);
    }
    return result(request, InferenceResult.Outcome.COMPLETED, stop, streamed);
  }

  private InferenceResult result(
      SimulatedRequest request, InferenceResult.Outcome outcome, StopSignal stop, long streamed) {
    String reason = outcome == InferenceResult.Outcome.STOPPED ? stop.reason().orElse(null) : null;
    return new InferenceResult(request, hostId, clock.instant(), outcome, reason, streamed);
  }

  private void pause(StopSignal stop) {
    if (chunkDelay.isZero()) {
      return;
    }
    try {
      Thread.sleep(chunkDelay.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      stop.stop("interrupted");
    }
  }

  private static String text(SplittableRandom random, int tokens) {
    StringBuilder sb = new StringBuilder();
    int words = Math.min(tokens, 8);
    for (int i = 0; i < words; i++) {
      sb.append("tok").append(random.nextInt(10_000)).append(' ');
    }
    return sb.toString();
  }
}
