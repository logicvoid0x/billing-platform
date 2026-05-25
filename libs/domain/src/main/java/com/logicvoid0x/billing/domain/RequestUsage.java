package com.logicvoid0x.billing.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The raw usage event, one per request, emitted by the inference host: facts only (IDs, model,
 * service tier, event time, token counts by meter), no prices or SKUs.
 *
 * @param requestId minted once by the gateway; the reservation ID and dedupe root
 * @param orgId the org billed; the Kafka key
 * @param workspaceId the workspace
 * @param apiKeyId the API key
 * @param model model identifier
 * @param serviceTier service tier
 * @param hostId the inference host that delivered output
 * @param eventTime when the usage happened (event time, used for hourly buckets)
 * @param quantities quantity per meter, {@code >= 0}
 */
public record RequestUsage(
    String requestId,
    String orgId,
    String workspaceId,
    String apiKeyId,
    String model,
    String serviceTier,
    String hostId,
    Instant eventTime,
    Map<MeterCode, Long> quantities) {
  /** Validates the event and copies the quantities into an immutable, code-ordered map. */
  public RequestUsage {
    Ids.require("requestId", requestId);
    Ids.requireSegment("orgId", orgId);
    Ids.require("workspaceId", workspaceId);
    Ids.require("apiKeyId", apiKeyId);
    Ids.require("model", model);
    Ids.require("serviceTier", serviceTier);
    Ids.require("hostId", hostId);
    Objects.requireNonNull(eventTime, "eventTime");
    TreeMap<MeterCode, Long> copy = new TreeMap<>();
    quantities.forEach((meter, quantity) ->
        copy.put(
            Objects.requireNonNull(meter, "meter"),
            Ids.requireNonNegative("quantity of " + meter, quantity)));
    quantities = Collections.unmodifiableSortedMap(copy);
  }

  /** The stream dedupe key for one meter of this request: {@code requestId:meterCode}. */
  public String dedupeKey(MeterCode meter) {
    return requestId + ":" + meter.code();
  }

  /** The event-time hour this request is aggregated into. */
  public Instant eventHour() {
    return eventTime.truncatedTo(ChronoUnit.HOURS);
  }

  /** Meters with a non-zero quantity, ordered by code; each becomes one metered line. */
  public List<MeterCode> nonZeroMeters() {
    return quantities.entrySet().stream()
        .filter(e -> e.getValue() > 0)
        .map(Map.Entry::getKey)
        .toList();
  }
}
