package com.logicvoid0x.billing.gateway;

import com.logicvoid0x.billing.admission.AdmissionController;
import com.logicvoid0x.billing.admission.AdmissionRequest;
import com.logicvoid0x.billing.admission.BudgetRef;
import com.logicvoid0x.billing.admission.Decision;
import com.logicvoid0x.billing.admission.LocalLease;
import com.logicvoid0x.billing.admission.StreamBudgetGuard;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.domain.SettleRecord;
import com.logicvoid0x.billing.domain.budget.BudgetStore;
import com.logicvoid0x.billing.rating.RatingLibrary;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * One gateway host's request path (plan: "mints request IDs, runs a per-minute limiter stub,
 * admits, proxies the simulator's stream, settles with the rating library, publishes SettleRecord
 * to budget.settle and the customer-visible usage to gateway.log").
 *
 * <ol>
 *   <li>Mint the request ID, the root of every downstream key, once per request.
 *   <li>Admit against holds for the prompt plus the first output chunk.
 *   <li>Proxy the stream; {@link StreamBudgetGuard} funds one chunk ahead and stops the stream
 *       cleanly when the next chunk cannot be held.
 *   <li>Settle the delivered usage: DIRECT on the shard, LEASE back into the local leases.
 *   <li>Publish the settle record and the gateway-log entry.
 * </ol>
 */
public final class Gateway {
  /** The outcome of one request. */
  public record Response(
      String requestId,
      Decision decision,
      String outcome,
      Map<MeterCode, Long> delivered,
      long settledCents,
      Map<String, Long> held,
      Map<String, Long> settled) {}

  /** Receives the stream for the client. */
  @FunctionalInterface
  public interface ClientSink {
    /** One chunk of output text. */
    void onChunk(String text);
  }

  /** Outcome when admission (or the per-minute limiter) refused the request. */
  public static final String REFUSED = "refused";

  private final String hostId;
  private final AdmissionController admission;
  private final BudgetStore store;
  private final Function<Instant, RatingLibrary> ratings;
  private final Upstream upstream;
  private final GatewayEvents events;
  private final MinuteLimiter limiter;
  private final Clock clock;
  private final long chunkTokens;
  private final Supplier<String> ids;
  private final UsagePricer pricer = new UsagePricer();

  /** Creates the gateway with random request IDs. */
  public Gateway(
      String hostId,
      AdmissionController admission,
      BudgetStore store,
      Function<Instant, RatingLibrary> ratings,
      Upstream upstream,
      GatewayEvents events,
      MinuteLimiter limiter,
      Clock clock,
      long chunkTokens) {
    this(
        hostId,
        admission,
        store,
        ratings,
        upstream,
        events,
        limiter,
        clock,
        chunkTokens,
        () -> "req_" + UUID.randomUUID().toString().replace("-", ""));
  }

  /** Creates the gateway. */
  public Gateway(
      String hostId,
      AdmissionController admission,
      BudgetStore store,
      Function<Instant, RatingLibrary> ratings,
      Upstream upstream,
      GatewayEvents events,
      MinuteLimiter limiter,
      Clock clock,
      long chunkTokens,
      Supplier<String> ids) {
    this.hostId = Objects.requireNonNull(hostId, "hostId");
    this.admission = Objects.requireNonNull(admission, "admission");
    this.store = Objects.requireNonNull(store, "store");
    this.ratings = Objects.requireNonNull(ratings, "ratings");
    this.upstream = Objects.requireNonNull(upstream, "upstream");
    this.events = Objects.requireNonNull(events, "events");
    this.limiter = Objects.requireNonNull(limiter, "limiter");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
    if (chunkTokens < 1) {
      throw new IllegalArgumentException("chunkTokens must be >= 1");
    }
    this.chunkTokens = chunkTokens;
  }

  /** Handles one request end to end. */
  public Response handle(GatewayRequest req, ClientSink client) {
    String requestId = ids.get();
    if (!limiter.tryAcquire(req.apiKeyId())) {
      return refused(
          requestId,
          new Decision(false, Decision.Path.REFUSED, java.util.List.of(), 0, "rate_limited"));
    }
    Instant now = clock.instant();
    RatingLibrary rating = ratings.apply(now);

    long firstChunk = Math.min(chunkTokens, req.maxOutputTokens());
    Map<String, Long> held =
        new HashMap<>(
            pricer.holds(rating, usage(requestId, req, now, true, firstChunk), req.budgets()));
    Decision decision =
        admission.admit(
            new AdmissionRequest(requestId, req.orgId(), req.budgets(), req.extraUsage()),
            Map.copyOf(held));
    if (!decision.admitted()) {
      return refused(requestId, decision);
    }

    IntFunction<Map<String, Long>> chunkHolds =
        chunk -> {
          Map<String, Long> h =
              pricer.holds(
                  rating,
                  usage(requestId, req, now, false, chunkTokens),
                  admittedBudgets(req, decision));
          h.forEach((b, v) -> held.merge(b, v, Math::addExact));
          return h;
        };
    StreamBudgetGuard guard = guard(requestId, decision, chunkHolds);
    Upstream.Result result =
        upstream.stream(
            requestId,
            req,
            (tokens, text) -> {
              client.onChunk(text);
              return guard == null ? Optional.empty() : guard.onTokens(Math.toIntExact(tokens));
            });

    Map<String, Long> settled =
        result.counts().values().stream().allMatch(q -> q == 0)
            ? zero(admittedBudgets(req, decision))
            : pricer.settle(
                rating,
                delivered(requestId, req, now, result.counts()),
                admittedBudgets(req, decision));
    int settleSequence = decision.sequence() + 1 + (guard == null ? 0 : guard.fundingAttempts());
    settleOnPath(requestId, decision, held, settled, settleSequence);

    String outcome =
        guard != null && guard.stopReason().isPresent()
            ? guard.stopReason().get()
            : result.stopReason().isPresent() ? result.stopReason().get() : "completed";
    long cents = UsagePricer.settledCents(settled, req.budgets());
    events.settled(
        new SettleRecord(
            requestId,
            req.orgId(),
            req.homeRegion(),
            now,
            onlyAdmitted(held, decision),
            settled,
            decision.path() == Decision.Path.FAIL_OPEN,
            rating.version(),
            settleSequence));
    events.logged(
        new GatewayEvents.LogEntry(
            requestId,
            req.orgId(),
            req.workspaceId(),
            req.apiKeyId(),
            req.model(),
            now,
            result.counts(),
            cents,
            outcome));
    return new Response(
        requestId, decision, outcome, result.counts(), cents, Map.copyOf(held), settled);
  }

  private StreamBudgetGuard guard(
      String requestId, Decision decision, IntFunction<Map<String, Long>> holds) {
    int start = decision.sequence() + 1;
    return switch (decision.path()) {
      case DIRECT -> StreamBudgetGuard.direct(store, requestId, start, chunkTokens, holds);
      case LEASE -> {
        Map<String, LocalLease> leases = new LinkedHashMap<>();
        for (String b : decision.budgets()) {
          leases.put(b, admission.lease(b));
        }
        yield StreamBudgetGuard.leased(leases, store, requestId, start, chunkTokens, holds, clock);
      }
      case FAIL_OPEN, REFUSED -> null; // fail-open is bounded by the allowance at admission
    };
  }

  /** Applies the settlement to where the holds were taken. */
  private void settleOnPath(
      String requestId,
      Decision decision,
      Map<String, Long> held,
      Map<String, Long> settled,
      int sequence) {
    switch (decision.path()) {
      case DIRECT -> store.settle(requestId, sequence, settled);
      case LEASE -> {
        long nowMillis = clock.millis();
        for (String b : decision.budgets()) {
          LocalLease lease = admission.lease(b);
          long diff = held.getOrDefault(b, 0L) - settled.getOrDefault(b, 0L);
          if (lease != null && diff > 0) {
            lease.refund(diff, nowMillis);
          } else if (lease != null && diff < 0) {
            lease.tryConsume(-diff, nowMillis); // overdraft beyond the holds is bounded by a chunk
          }
        }
      }
      case FAIL_OPEN, REFUSED -> {}
    }
  }

  private Response refused(String requestId, Decision decision) {
    return new Response(requestId, decision, REFUSED, Map.of(), 0, Map.of(), Map.of());
  }

  private static java.util.List<BudgetRef> admittedBudgets(GatewayRequest req, Decision decision) {
    return req.budgets().stream().filter(b -> decision.budgets().contains(b.budgetId())).toList();
  }

  private static Map<String, Long> onlyAdmitted(Map<String, Long> held, Decision decision) {
    Map<String, Long> out = new HashMap<>();
    for (String b : decision.budgets()) {
      out.put(b, held.getOrDefault(b, 0L));
    }
    return out;
  }

  private static Map<String, Long> zero(java.util.List<BudgetRef> budgets) {
    Map<String, Long> out = new HashMap<>();
    budgets.forEach(b -> out.put(b.budgetId(), 0L));
    return out;
  }

  /** The worst-case usage of the prompt (if {@code withPrompt}) plus {@code outputTokens}. */
  private RequestUsage usage(
      String requestId, GatewayRequest req, Instant at, boolean withPrompt, long outputTokens) {
    Map<MeterCode, Long> q = new HashMap<>();
    if (withPrompt) {
      q.put(MeterCode.INPUT_TOKENS, req.inputTokens());
      q.put(MeterCode.CACHE_READ_TOKENS, req.cacheReadTokens());
      q.put(MeterCode.CACHE_WRITE_TOKENS, req.cacheWriteTokens());
    }
    q.put(MeterCode.OUTPUT_TOKENS, outputTokens);
    return new RequestUsage(
        requestId,
        req.orgId(),
        req.workspaceId(),
        req.apiKeyId(),
        req.model(),
        req.serviceTier(),
        hostId,
        at,
        q);
  }

  private RequestUsage delivered(
      String requestId, GatewayRequest req, Instant at, Map<MeterCode, Long> counts) {
    return new RequestUsage(
        requestId,
        req.orgId(),
        req.workspaceId(),
        req.apiKeyId(),
        req.model(),
        req.serviceTier(),
        hostId,
        at,
        counts);
  }
}
