package com.logicvoid0x.billing.rating;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.MeteredLine;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.money.Cents;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** {@link RatingLibrary} over an immutable {@link RatingCatalog} snapshot. */
public final class CatalogRatingLibrary implements RatingLibrary {
  /** Band used when no context-band rule applies to a model. */
  public static final String DEFAULT_BAND = "base";

  private final RatingCatalog catalog;
  private final String version;

  /**
   * Creates the library.
   *
   * @param catalog the catalog snapshot
   * @param codeVersion version of the rating code; combined with the catalog version
   */
  public CatalogRatingLibrary(RatingCatalog catalog, String codeVersion) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.version = Strings.require("codeVersion", codeVersion) + "/" + catalog.version();
  }

  @Override
  public List<MeteredLine> resolve(RequestUsage usage, Instant eventTime) {
    String band =
        find(
                catalog.bandRules(),
                r -> r.model().equals(usage.model()),
                ContextBandRule::effective,
                eventTime)
            .map(r -> r.bandOf(usage))
            .orElse(DEFAULT_BAND);
    List<MeteredLine> lines = new ArrayList<>();
    for (MeterCode meter : usage.nonZeroMeters()) {
      Sku sku =
          find(
                  catalog.skus(),
                  s ->
                      s.meter().equals(meter)
                          && s.model().equals(usage.model())
                          && s.serviceTier().equals(usage.serviceTier())
                          && s.contextBand().equals(band),
                  Sku::effective,
                  eventTime)
              .orElseThrow(() ->
                  new RatingException(
                      "no SKU for "
                          + meter.code()
                          + "/"
                          + usage.model()
                          + "/"
                          + usage.serviceTier()
                          + "/"
                          + band
                          + " at "
                          + eventTime));
      lines.add(
          new MeteredLine(
              usage.requestId(),
              usage.orgId(),
              usage.workspaceId(),
              usage.apiKeyId(),
              sku.skuId(),
              meter,
              usage.quantities().get(meter),
              eventTime));
    }
    return List.copyOf(lines);
  }

  @Override
  public Cents.Rate rate(String orgId, String skuId, Instant hour) {
    return pricing(orgId, skuId, hour)
        .flatRate()
        .orElseThrow(() -> new RatingException("graduated pricing has no single rate: " + skuId));
  }

  @Override
  public Pricing pricing(String orgId, String skuId, Instant hour) {
    if (!EffectivePeriod.isHour(hour)) {
      throw new IllegalArgumentException("rating takes an hour boundary, got " + hour);
    }
    Optional<Pricing> contract =
        find(
                catalog.contractPrices(),
                c -> c.orgId().equals(orgId) && c.skuId().equals(skuId),
                ContractPrice::effective,
                hour)
            .map(ContractPrice::pricing);
    if (contract.isPresent()) {
      return contract.get();
    }
    return find(catalog.listPrices(), p -> p.skuId().equals(skuId), ListPrice::effective, hour)
        .map(ListPrice::pricing)
        .orElseThrow(() -> new RatingException("no price for " + skuId + " at " + hour));
  }

  @Override
  public long chargeCents(
      String orgId, String skuId, long totalBefore, long totalAfter, Instant hour) {
    return pricing(orgId, skuId, hour).charge(totalBefore, totalAfter);
  }

  @Override
  public long usageUnits(String skuId, long quantity, Instant at) {
    Weight w =
        find(catalog.weights(), x -> x.skuId().equals(skuId), Weight::effective, at)
            .orElseThrow(() -> new RatingException("no weight for " + skuId + " at " + at));
    return Cents.forTotal(quantity, w.unitsPer());
  }

  @Override
  public String version() {
    return version;
  }

  /** The row matching {@code filter} in effect at {@code at}; the catalog forbids overlaps. */
  private static <T> Optional<T> find(
      List<T> rows, Predicate<T> filter, Function<T, EffectivePeriod> period, Instant at) {
    return rows.stream().filter(filter).filter(r -> period.apply(r).contains(at)).findFirst();
  }
}
