package com.logicvoid0x.billing.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.admission.AdmissionController;
import com.logicvoid0x.billing.admission.BudgetRef;
import com.logicvoid0x.billing.admission.BudgetResolver;
import com.logicvoid0x.billing.admission.Decision;
import com.logicvoid0x.billing.admission.ModeCache;
import com.logicvoid0x.billing.domain.BudgetKind;
import com.logicvoid0x.billing.domain.FailPolicy;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.SettleRecord;
import com.logicvoid0x.billing.domain.budget.InMemoryBudgetStore;
import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayTest {
  private static final String BAL = "org_acme:balance";
  private static final BudgetRef BALANCE =
      new BudgetRef(BAL, BudgetKind.BALANCE, FailPolicy.closed());
  private static final RatingLibrary GOLDEN =
      new CatalogRatingLibrary(GoldenCases.catalog(), "gw-test");
  private static final long CHUNK = 2_048;

  /** Streams chunks of up to CHUNK tokens until maxOutput or until the sink asks to stop. */
  static final class FakeUpstream implements Upstream {
    final AtomicInteger streams = new AtomicInteger();

    @Override
    public Result stream(String requestId, GatewayRequest request, ChunkSink sink) {
      streams.incrementAndGet();
      long out = 0;
      while (out < request.maxOutputTokens()) {
        long n = Math.min(512, request.maxOutputTokens() - out);
        out += n;
        Optional<String> stop = sink.onChunk(n, "x".repeat((int) Math.min(n, 3)));
        if (stop.isPresent()) {
          return new Result(counts(out), "stopped", stop);
        }
      }
      return new Result(counts(out), "completed", Optional.empty());
    }

    private static Map<MeterCode, Long> counts(long out) {
      return Map.of(MeterCode.OUTPUT_TOKENS, out);
    }
  }

  static final class RecordingEvents implements GatewayEvents {
    final List<SettleRecord> settles = new ArrayList<>();
    final List<LogEntry> logs = new ArrayList<>();

    @Override
    public void settled(SettleRecord record) {
      settles.add(record);
    }

    @Override
    public void logged(LogEntry entry) {
      logs.add(entry);
    }
  }

  private InMemoryBudgetStore shard;
  private RecordingEvents events;
  private FakeUpstream upstream;
  private int nextId;

  private Gateway gateway(long perMinute) {
    Clock clock = Clock.fixed(GoldenCases.HOUR.plusSeconds(60), ZoneOffset.UTC);
    shard = new InMemoryBudgetStore(1, clock);
    AdmissionController admission =
        new AdmissionController("gw-1", shard, new ModeCache(), new BudgetResolver(), clock);
    events = new RecordingEvents();
    upstream = new FakeUpstream();
    return new Gateway(
        "gw-1",
        admission,
        shard,
        t -> GOLDEN,
        upstream,
        events,
        new MinuteLimiter(perMinute, clock),
        clock,
        CHUNK,
        () -> "req_" + ++nextId);
  }

  private static GatewayRequest request(long maxOutput) {
    return new GatewayRequest(
        "org_acme",
        "ws_1",
        "key_1",
        "model-x",
        "standard",
        0,
        0,
        0,
        maxOutput,
        List.of(BALANCE),
        true,
        "us-east",
        1);
  }

  @BeforeEach
  void reset() {
    nextId = 0;
  }

  @Test
  void aFundedRequestStreamsSettlesAtTheContractPriceAndPublishesBothRecords() {
    Gateway gw = gateway(100);
    shard.open(BAL, "org_acme", 1_000_000);
    List<String> client = new ArrayList<>();

    Gateway.Response r = gw.handle(request(1_000_000), client::add);

    assertThat(r.requestId()).isEqualTo("req_1");
    assertThat(r.decision().admitted()).isTrue();
    assertThat(r.decision().path()).isEqualTo(Decision.Path.DIRECT);
    assertThat(r.outcome()).isEqualTo("completed");
    assertThat(r.delivered()).containsEntry(MeterCode.OUTPUT_TOKENS, 1_000_000L);
    // Acme's contract: $12/M output, so 1M tokens settle at 1,200 cents.
    assertThat(r.settledCents()).isEqualTo(1_200);
    assertThat(shard.balance(BAL).spent()).isEqualTo(1_200);
    assertThat(shard.balance(BAL).held()).as("every hold released at settle").isZero();
    assertThat(client).isNotEmpty();

    assertThat(events.settles)
        .singleElement()
        .satisfies(s -> {
          assertThat(s.requestId()).isEqualTo("req_1");
          assertThat(s.settledByBudget()).containsEntry(BAL, 1_200L);
          assertThat(s.heldByBudget().get(BAL)).isGreaterThanOrEqualTo(1_200L);
          assertThat(s.failOpen()).isFalse();
          assertThat(s.ratingLibraryVersion()).isEqualTo("gw-test/golden-1");
        });
    assertThat(events.logs)
        .singleElement()
        .satisfies(l -> {
          assertThat(l.settledCents()).isEqualTo(1_200);
          assertThat(l.outcome()).isEqualTo("completed");
        });
  }

  @Test
  void anUnfundedStreamStopsCleanlyAndSettlesOnlyWhatWasDelivered() {
    Gateway gw = gateway(100);
    shard.open(BAL, "org_acme", 10); // a few chunks of credit
    Gateway.Response r = gw.handle(request(10_000_000), text -> {});
    assertThat(r.outcome()).isEqualTo("budget_exhausted");
    long delivered = r.delivered().get(MeterCode.OUTPUT_TOKENS);
    assertThat(delivered).isLessThan(10_000_000L).isGreaterThan(0L);
    assertThat(r.settledCents())
        .isEqualTo(GOLDEN.chargeCents("org_acme", "sku_out", 0, delivered, GoldenCases.HOUR));
    assertThat(shard.balance(BAL).held()).isZero();
    assertThat(events.logs.get(0).outcome()).isEqualTo("budget_exhausted");
  }

  @Test
  void refusedRequestsNeverReachTheHostAndPublishNothing() {
    Gateway gw = gateway(1);
    shard.open(BAL, "org_acme", 0);
    assertThat(gw.handle(request(100), t -> {}).outcome()).isEqualTo(Gateway.REFUSED);
    assertThat(gw.handle(request(100), t -> {}).decision().reason()).isEqualTo("rate_limited");
    assertThat(upstream.streams).hasValue(0);
    assertThat(events.settles).isEmpty();
    assertThat(events.logs).isEmpty();
  }

  @Test
  void settlementTelescopesAcrossRequestsOnTheHost() {
    Gateway gw = gateway(1_000);
    shard.open(BAL, "org_acme", 10_000_000);
    long cents = 0;
    for (int i = 0; i < 30; i++) {
      cents += gw.handle(request(333_333), t -> {}).settledCents();
    }
    // Rounded once on the running total, not per request: 9,999,990 tokens at $12/M.
    assertThat(cents)
        .isEqualTo(GOLDEN.chargeCents("org_acme", "sku_out", 0, 30L * 333_333, GoldenCases.HOUR));
  }
}
