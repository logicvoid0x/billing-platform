package com.logicvoid0x.billing.inferencesim;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** What a simulated request did: outcome, output delivered, and token counts by meter. */
public final class InferenceResult {
  /** How the stream ended. */
  public enum Outcome {
    /** All output was generated. */
    COMPLETED,
    /** A stop signal ended the stream at a chunk boundary. */
    STOPPED
  }

  private final SimulatedRequest request;
  private final String hostId;
  private final Instant eventTime;
  private final Outcome outcome;
  private final String stopReason;
  private final long outputTokens;
  private final Map<MeterCode, Long> counts;

  InferenceResult(
      SimulatedRequest request,
      String hostId,
      Instant eventTime,
      Outcome outcome,
      String stopReason,
      long outputTokens) {
    this.request = request;
    this.hostId = hostId;
    this.eventTime = eventTime;
    this.outcome = outcome;
    this.stopReason = stopReason;
    this.outputTokens = outputTokens;
    TreeMap<MeterCode, Long> c = new TreeMap<>();
    putIfPositive(c, MeterCode.INPUT_TOKENS, request.inputTokens());
    putIfPositive(c, MeterCode.CACHE_READ_TOKENS, request.cacheReadTokens());
    putIfPositive(c, MeterCode.CACHE_WRITE_TOKENS, request.cacheWriteTokens());
    putIfPositive(c, MeterCode.OUTPUT_TOKENS, outputTokens);
    this.counts = Collections.unmodifiableSortedMap(c);
  }

  private static void putIfPositive(Map<MeterCode, Long> m, MeterCode meter, long n) {
    if (n > 0) {
      m.put(meter, n);
    }
  }

  /** The request ID. */
  public String requestId() {
    return request.requestId();
  }

  /** How the stream ended. */
  public Outcome outcome() {
    return outcome;
  }

  /** Why it stopped, if it did. */
  public Optional<String> stopReason() {
    return Optional.ofNullable(stopReason);
  }

  /** Output tokens actually streamed. */
  public long outputTokens() {
    return outputTokens;
  }

  /** Token counts by meter (non-zero meters only), ordered by code. */
  public Map<MeterCode, Long> counts() {
    return counts;
  }

  /**
   * The usage event to emit, under the original request ID, only if this attempt delivered output;
   * an attempt that delivered nothing emits nothing.
   */
  public Optional<RequestUsage> usageIfDelivered() {
    if (outputTokens == 0) {
      return Optional.empty();
    }
    return Optional.of(
        new RequestUsage(
            request.requestId(),
            request.orgId(),
            request.workspaceId(),
            request.apiKeyId(),
            request.model(),
            request.serviceTier(),
            hostId,
            eventTime,
            counts));
  }

  /** JSON-friendly view with snake_case keys and meter codes as strings. */
  public Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("request_id", request.requestId());
    json.put("outcome", outcome.name());
    json.put("stop_reason", stopReason);
    json.put("output_tokens", outputTokens);
    Map<String, Long> byCode = new LinkedHashMap<>();
    counts.forEach((m, n) -> byCode.put(m.code(), n));
    json.put("counts", byCode);
    return json;
  }
}
