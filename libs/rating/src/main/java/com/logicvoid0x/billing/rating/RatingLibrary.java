package com.logicvoid0x.billing.rating;

import com.logicvoid0x.billing.domain.MeteredLine;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.money.Cents;
import java.time.Instant;
import java.util.List;

/**
 * One rating library for holds, settlement and invoices (design seam 1): the same inputs give the
 * same amount everywhere, and its version is recorded on every settle and rated row.
 */
public interface RatingLibrary {
  /** One metered line per non-zero meter, with the SKU including the context band. */
  List<MeteredLine> resolve(RequestUsage usage, Instant eventTime);

  /** The flat rate in effect for the org at {@code hour}; throws for graduated pricing. */
  Cents.Rate rate(String orgId, String skuId, Instant hour);

  /** The pricing in effect for the org at {@code hour}: contract override, else list price. */
  Pricing pricing(String orgId, String skuId, Instant hour);

  /**
   * Charge in cents for moving the running total from {@code totalBefore} to {@code totalAfter}.
   */
  long chargeCents(String orgId, String skuId, long totalBefore, long totalAfter, Instant hour);

  /** Allowance usage units for {@code quantity} of a SKU, from the weight book. */
  long usageUnits(String skuId, long quantity, Instant at);

  /** Library and catalog version, recorded on every settle and rated row. */
  String version();
}
