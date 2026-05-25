package com.logicvoid0x.billing.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * One non-zero meter of a request, resolved to a SKU (meter plus every price-changing dimension).
 *
 * @param requestId the request
 * @param orgId the org billed
 * @param skuId opaque SKU ID from the catalog
 * @param meter the meter
 * @param quantity units, {@code > 0}
 * @param eventTime event time of the request
 */
public record MeteredLine(
    String requestId,
    String orgId,
    String skuId,
    MeterCode meter,
    long quantity,
    Instant eventTime) {
  /** Validates the line. */
  public MeteredLine {
    Ids.require("requestId", requestId);
    Ids.requireSegment("orgId", orgId);
    Ids.require("skuId", skuId);
    Objects.requireNonNull(meter, "meter");
    Objects.requireNonNull(eventTime, "eventTime");
    if (quantity <= 0) {
      throw new IllegalArgumentException("a metered line needs a quantity > 0");
    }
  }

  /** The stream dedupe key: {@code requestId:meterCode}. */
  public String dedupeKey() {
    return requestId + ":" + meter.code();
  }

  /** The event-time hour this line is aggregated into. */
  public Instant eventHour() {
    return eventTime.truncatedTo(ChronoUnit.HOURS);
  }
}
