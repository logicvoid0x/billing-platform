package com.logicvoid0x.billing.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Published by the gateway on {@code budget.settle} when a request settles; joined to rated usage
 * by {@code requestId}. Amounts are in each budget's unit: {@code long} cents for money budgets,
 * usage units for allowances.
 *
 * @param requestId same ID as the {@link RequestUsage} and the reservation
 * @param orgId the org
 * @param homeRegion the home region of the org
 * @param eventTime bucketed by the same event-time hour as metering
 * @param heldByBudget budgetId to amount held
 * @param settledByBudget budgetId to amount actually spent
 * @param failOpen whether the request was admitted under the fail-open allowance
 * @param ratingLibraryVersion version of the rating library that priced the settle
 * @param sequence a retried settle reuses its sequence
 */
public record SettleRecord(
    String requestId,
    String orgId,
    String homeRegion,
    Instant eventTime,
    Map<String, Long> heldByBudget,
    Map<String, Long> settledByBudget,
    boolean failOpen,
    String ratingLibraryVersion,
    long sequence) {
  /** Validates the record and copies the maps into immutable, ID-ordered maps. */
  public SettleRecord {
    Ids.require("requestId", requestId);
    Ids.requireSegment("orgId", orgId);
    Ids.require("homeRegion", homeRegion);
    Objects.requireNonNull(eventTime, "eventTime");
    Ids.require("ratingLibraryVersion", ratingLibraryVersion);
    Ids.requireNonNegative("sequence", sequence);
    heldByBudget = copy("held", heldByBudget);
    settledByBudget = copy("settled", settledByBudget);
  }

  /** Idempotency key of the settle: {@code requestId:sequence}. */
  public String idempotencyKey() {
    return requestId + ":" + sequence;
  }

  /** The event-time hour used to join with metering. */
  public Instant eventHour() {
    return eventTime.truncatedTo(ChronoUnit.HOURS);
  }

  private static Map<String, Long> copy(String what, Map<String, Long> amounts) {
    TreeMap<String, Long> copy = new TreeMap<>();
    amounts.forEach((budgetId, amount) ->
        copy.put(
            Ids.require("budgetId", budgetId),
            Ids.requireNonNegative(what + " amount for " + budgetId, amount)));
    return Collections.unmodifiableSortedMap(copy);
  }
}
