package com.logicvoid0x.billing.rating;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * An immutable snapshot of the catalogs the rating library reads: SKUs, context-band rules, the
 * price book, contract overrides and the allowance weight book. Validated on construction: unique
 * SKU IDs, known SKUs everywhere, and no overlapping effective periods for the same key.
 *
 * @param version catalog version, recorded on rated rows
 * @param skus every SKU
 * @param bandRules context-band rules
 * @param listPrices price book
 * @param contractPrices contract overrides
 * @param weights allowance weight book
 */
public record RatingCatalog(
    String version,
    List<Sku> skus,
    List<ContextBandRule> bandRules,
    List<ListPrice> listPrices,
    List<ContractPrice> contractPrices,
    List<Weight> weights) {
  /** Validates and copies the catalog. */
  public RatingCatalog {
    Strings.require("version", version);
    skus = List.copyOf(skus);
    bandRules = List.copyOf(bandRules);
    listPrices = List.copyOf(listPrices);
    contractPrices = List.copyOf(contractPrices);
    weights = List.copyOf(weights);

    Set<String> skuIds = new HashSet<>();
    for (Sku s : skus) {
      if (!skuIds.add(s.skuId())) {
        throw new IllegalArgumentException("duplicate sku_id " + s.skuId());
      }
    }
    requireKnown(skuIds, listPrices, ListPrice::skuId);
    requireKnown(skuIds, contractPrices, ContractPrice::skuId);
    requireKnown(skuIds, weights, Weight::skuId);
    requireNoOverlap(listPrices, ListPrice::skuId, ListPrice::effective, "list price");
    requireNoOverlap(
        contractPrices, c -> c.orgId() + "/" + c.skuId(), ContractPrice::effective, "contract");
    requireNoOverlap(weights, Weight::skuId, Weight::effective, "weight");
    requireNoOverlap(bandRules, ContextBandRule::model, ContextBandRule::effective, "band rule");
    requireNoOverlap(
        skus,
        s -> s.meter().code() + "/" + s.model() + "/" + s.serviceTier() + "/" + s.contextBand(),
        Sku::effective,
        "SKU dimensions");
  }

  private static <T> void requireKnown(
      Set<String> skuIds, List<T> rows, Function<T, String> skuId) {
    for (T row : rows) {
      if (!skuIds.contains(skuId.apply(row))) {
        throw new IllegalArgumentException("unknown sku_id " + skuId.apply(row));
      }
    }
  }

  private static <T> void requireNoOverlap(
      List<T> rows, Function<T, String> key, Function<T, EffectivePeriod> period, String what) {
    Map<String, List<EffectivePeriod>> byKey = new HashMap<>();
    for (T row : rows) {
      List<EffectivePeriod> seen = byKey.computeIfAbsent(key.apply(row), k -> new ArrayList<>());
      EffectivePeriod p = period.apply(row);
      for (EffectivePeriod other : seen) {
        if (p.overlaps(other)) {
          throw new IllegalArgumentException(what + " periods overlap for " + key.apply(row));
        }
      }
      seen.add(p);
    }
  }
}
