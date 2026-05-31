package com.logicvoid0x.billing.rating;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.money.Cents.Rate;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A small catalog shared by the rating tests. */
final class Fixtures {
  private Fixtures() {}

  static final Instant V1 = Instant.parse("2026-01-01T00:00:00Z");
  static final Instant V2 = Instant.parse("2026-11-15T00:00:00Z");
  static final Rate FIFTEEN_PER_M = new Rate(1500, 1_000_000);
  static final Rate THREE_PER_M = new Rate(300, 1_000_000);
  static final Rate SIX_PER_M = new Rate(600, 1_000_000);

  static Sku sku(String id, MeterCode meter, String band) {
    return new Sku(
        id,
        "model-x/standard/" + meter.code() + "/" + band,
        meter,
        "model-x",
        "standard",
        band,
        EffectivePeriod.from(V1));
  }

  static RatingCatalog catalog() {
    return new RatingCatalog(
        "catalog-1",
        List.of(
            sku("sku_in", MeterCode.INPUT_TOKENS, "base"),
            sku("sku_in_long", MeterCode.INPUT_TOKENS, "long"),
            sku("sku_out", MeterCode.OUTPUT_TOKENS, "base"),
            sku("sku_out_long", MeterCode.OUTPUT_TOKENS, "long"),
            sku("sku_cache_read", MeterCode.CACHE_READ_TOKENS, "base"),
            sku("sku_cache_read_long", MeterCode.CACHE_READ_TOKENS, "long")),
        List.of(new ContextBandRule("model-x", 200_000, "base", "long", EffectivePeriod.from(V1))),
        List.of(
            new ListPrice("sku_in", Pricing.flat(THREE_PER_M), EffectivePeriod.from(V1)),
            new ListPrice("sku_in_long", Pricing.flat(SIX_PER_M), EffectivePeriod.from(V1)),
            new ListPrice("sku_out", Pricing.flat(FIFTEEN_PER_M), EffectivePeriod.between(V1, V2)),
            new ListPrice(
                "sku_out", Pricing.flat(new Rate(1800, 1_000_000)), EffectivePeriod.from(V2)),
            new ListPrice(
                "sku_out_long", Pricing.flat(new Rate(2250, 1_000_000)), EffectivePeriod.from(V1)),
            new ListPrice(
                "sku_cache_read", Pricing.flat(new Rate(30, 1_000_000)), EffectivePeriod.from(V1)),
            new ListPrice(
                "sku_cache_read_long",
                Pricing.flat(new Rate(60, 1_000_000)),
                EffectivePeriod.from(V1))),
        List.of(
            ContractPrice.discount(
                "org_acme",
                "sku_out",
                Pricing.flat(FIFTEEN_PER_M),
                2000,
                EffectivePeriod.from(V1))),
        List.of(
            new Weight("sku_in", new Rate(1, 1), EffectivePeriod.from(V1)),
            new Weight("sku_out", new Rate(5, 1), EffectivePeriod.from(V1)),
            new Weight("sku_cache_read", new Rate(1, 10), EffectivePeriod.from(V1))));
  }

  static RequestUsage usage(Instant at, Map<MeterCode, Long> quantities) {
    return new RequestUsage(
        "req_1", "org_acme", "ws_1", "key_1", "model-x", "standard", "host-a", at, quantities);
  }
}
