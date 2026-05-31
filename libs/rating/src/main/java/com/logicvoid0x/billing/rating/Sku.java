package com.logicvoid0x.billing.rating;

import com.logicvoid0x.billing.domain.MeterCode;
import java.util.Objects;

/**
 * A SKU: a meter plus every price-changing dimension (model, service tier, context band). SKUs are
 * never edited; a change is a new SKU or a new effective-dated row.
 *
 * @param skuId opaque, permanent ID
 * @param key readable key, e.g. {@code model-x/standard/output_tokens/base}
 * @param meter the meter it counts
 * @param model model identifier
 * @param serviceTier service tier
 * @param contextBand context band, e.g. {@code base} or {@code long}
 * @param effective when the SKU can be resolved
 */
public record Sku(
    String skuId,
    String key,
    MeterCode meter,
    String model,
    String serviceTier,
    String contextBand,
    EffectivePeriod effective) {
  /** Validates the SKU. */
  public Sku {
    Strings.require("skuId", skuId);
    Strings.require("key", key);
    Objects.requireNonNull(meter, "meter");
    Strings.require("model", model);
    Strings.require("serviceTier", serviceTier);
    Strings.require("contextBand", contextBand);
    Objects.requireNonNull(effective, "effective");
  }
}
