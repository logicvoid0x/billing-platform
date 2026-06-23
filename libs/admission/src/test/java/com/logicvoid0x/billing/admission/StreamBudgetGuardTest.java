package com.logicvoid0x.billing.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.budget.InMemoryBudgetStore;
import com.logicvoid0x.billing.money.Cents;
import com.logicvoid0x.billing.money.Cents.Rate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamBudgetGuardTest {
  private static final long T0 = 1_793_000_000_000L;
  private static final String B = "org_1:balance";
  private static final int CHUNK = 2_048;
  private static final Rate RATE = new Rate(1500, 1_000_000); // $15 per million output tokens

  /** Hold for chunk i: Cents.hold on the running output total, so holds never round per chunk. */
  private static Map<String, Long> holds(int i) {
    return Map.of(B, Cents.hold((long) i * CHUNK, CHUNK, RATE));
  }

  private InMemoryBudgetStore shard;
  private Clock clock;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(Instant.ofEpochMilli(T0), ZoneOffset.UTC);
    shard = new InMemoryBudgetStore(1, clock);
  }

  private StreamBudgetGuard directGuard(long capacity) {
    shard.open(B, "org_1", capacity);
    assertThat(shard.reserve("req_1", 0, holds(0)).admitted()).isTrue();
    return StreamBudgetGuard.direct(shard, "req_1", 0, CHUNK, StreamBudgetGuardTest::holds);
  }

  /** Streams {@code tokens} in pieces of {@code piece}; returns tokens delivered before a stop. */
  private static long stream(StreamBudgetGuard guard, long tokens, int piece) {
    long delivered = 0;
    while (delivered < tokens) {
      int n = (int) Math.min(piece, tokens - delivered);
      delivered += n;
      if (guard.onTokens(n).isPresent()) {
        break;
      }
    }
    return delivered;
  }

  @Test
  void fundsTheNextChunkAtSeventyFivePercentOfTheCurrentOne() {
    StreamBudgetGuard guard = directGuard(1_000_000);
    assertThat(guard.onTokens(1_535)).isEmpty(); // 74.95% of 2,048
    assertThat(guard.fundedTokens()).isEqualTo(CHUNK);
    assertThat(guard.onTokens(1)).isEmpty(); // 1,536 = 75%
    assertThat(guard.fundedTokens()).isEqualTo(2L * CHUNK);
    assertThat(shard.balance(B).held()).isEqualTo(holds(0).get(B) + holds(1).get(B));
  }

  @Test
  void aWellFundedStreamRunsToTheEnd() {
    StreamBudgetGuard guard = directGuard(1_000_000);
    assertThat(stream(guard, 50_000, 100)).isEqualTo(50_000);
    assertThat(guard.stopReason()).isEmpty();
    assertThat(guard.fundedTokens()).isGreaterThanOrEqualTo(50_000);
    assertThat(guard.fundedTokens() - 50_000)
        .as("at most one chunk funded ahead")
        .isLessThanOrEqualTo(CHUNK);
  }

  @Test
  void anUnfundedStreamStopsCleanlyAtTheFundedBoundary() {
    // Enough for exactly three chunk holds (each 3 or 4 cents incl. the +1 for concurrency).
    long threeChunks = holds(0).get(B) + holds(1).get(B) + holds(2).get(B);
    StreamBudgetGuard guard = directGuard(threeChunks);
    long delivered = stream(guard, 100_000, 100);
    assertThat(guard.stopReason()).contains("budget_exhausted");
    assertThat(guard.fundedTokens()).isEqualTo(3L * CHUNK);
    assertThat(delivered)
        .as("stops at the boundary, never before it, at most one piece past it")
        .isBetween(3L * CHUNK, 3L * CHUNK + 99);
    assertThat(shard.balance(B).available()).isZero();
  }

  @Test
  void theStopVerdictRepeatsAndNoMoreFundingIsAsked() {
    long oneChunk = holds(0).get(B);
    StreamBudgetGuard guard = directGuard(oneChunk);
    stream(guard, 100_000, 512);
    assertThat(guard.onTokens(1)).contains("budget_exhausted");
    assertThat(guard.fundingAttempts()).isEqualTo(1);
  }

  @Test
  void leaseStreamsDrawFromTheLeaseThenFallBackToTheShard() {
    shard.open(B, "org_1", 1_000_000);
    LocalLease lease = new LocalLease(B);
    // Chunk 0 was admitted from the lease; the lease has room for two more chunk holds.
    lease.install(holds(1).get(B) + holds(2).get(B), 0, T0 + 2_000);
    StreamBudgetGuard guard =
        StreamBudgetGuard.leased(
            Map.of(B, lease), shard, "req_1", 0, CHUNK, StreamBudgetGuardTest::holds, clock);
    assertThat(stream(guard, 5L * CHUNK, 256)).isEqualTo(5L * CHUNK);
    assertThat(lease.remaining()).isZero();
    assertThat(shard.balance(B).held())
        .as("chunks 3+ were reserved on the shard")
        .isEqualTo(holds(3).get(B) + holds(4).get(B) + holds(5).get(B));
  }

  @Test
  void aShardOutageStopsAtTheBoundaryWithItsOwnReason() {
    AdmissionControllerTest.FlakyStore flaky = new AdmissionControllerTest.FlakyStore(shard);
    flaky.down.set(true);
    StreamBudgetGuard guard =
        StreamBudgetGuard.direct(flaky, "req_1", 0, CHUNK, StreamBudgetGuardTest::holds);
    stream(guard, 100_000, 100);
    assertThat(guard.stopReason()).contains("shard_unavailable");
  }
}
