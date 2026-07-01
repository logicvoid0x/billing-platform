package com.logicvoid0x.billing.catalog.launch;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.catalog.launch.LaunchGate.Gap;
import com.logicvoid0x.billing.catalog.launch.LaunchGate.Reason;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.money.Cents.Rate;
import com.logicvoid0x.billing.rating.ContextBandRule;
import com.logicvoid0x.billing.rating.EffectivePeriod;
import com.logicvoid0x.billing.rating.ListPrice;
import com.logicvoid0x.billing.rating.Pricing;
import com.logicvoid0x.billing.rating.RatingCatalog;
import com.logicvoid0x.billing.rating.Sku;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LaunchGateTest {
  private static final Instant V1 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant LAUNCH = Instant.parse("2026-12-01T09:00:00Z");
  private static final List<ModelOffering> REGISTRY =
      List.of(
          new ModelOffering("model-x", "standard", MeterCode.INPUT_TOKENS),
          new ModelOffering("model-x", "standard", MeterCode.OUTPUT_TOKENS));

  private final List<Sku> skus = new ArrayList<>();
  private final List<ListPrice> prices = new ArrayList<>();
  private final List<ContextBandRule> rules = new ArrayList<>();

  private void priced(String id, MeterCode meter, String band, Instant from) {
    skus.add(
        new Sku(id, id + "/key", meter, "model-x", "standard", band, EffectivePeriod.from(from)));
    prices.add(new ListPrice(id, Pricing.flat(new Rate(300, 1_000_000)), EffectivePeriod.from(V1)));
  }

  private LaunchGate.Result check(Optional<String> model) {
    RatingCatalog catalog =
        new RatingCatalog("catalog-v9", skus, rules, prices, List.of(), List.of());
    return LaunchGate.check(catalog, REGISTRY, LAUNCH, model);
  }

  @Test
  void passesWhenEveryRegisteredMeterHasAPricedSku() {
    priced("in", MeterCode.INPUT_TOKENS, "base", V1);
    priced("out", MeterCode.OUTPUT_TOKENS, "base", V1);
    LaunchGate.Result r = check(Optional.empty());
    assertThat(r.passed()).isTrue();
    assertThat(r.catalogVersion()).isEqualTo("catalog-v9");
  }

  @Test
  void failsForAMeterWithoutASku() {
    priced("in", MeterCode.INPUT_TOKENS, "base", V1);
    assertThat(check(Optional.empty()).gaps())
        .containsExactly(new Gap("model-x", "standard", "output_tokens", "base", Reason.NO_SKU));
  }

  @Test
  void aBandRuleRequiresSkusInBothBands() {
    rules.add(new ContextBandRule("model-x", 200_000, "base", "long", EffectivePeriod.from(V1)));
    priced("in", MeterCode.INPUT_TOKENS, "base", V1);
    priced("out", MeterCode.OUTPUT_TOKENS, "base", V1);
    priced("in_long", MeterCode.INPUT_TOKENS, "long", V1);
    assertThat(check(Optional.empty()).gaps())
        .containsExactly(new Gap("model-x", "standard", "output_tokens", "long", Reason.NO_SKU));
  }

  @Test
  void aSkuWithoutAListPriceFails() {
    priced("in", MeterCode.INPUT_TOKENS, "base", V1);
    skus.add(
        new Sku(
            "out",
            "out/key",
            MeterCode.OUTPUT_TOKENS,
            "model-x",
            "standard",
            "base",
            EffectivePeriod.from(V1)));
    assertThat(check(Optional.empty()).gaps())
        .extracting(Gap::reason)
        .containsExactly(Reason.NO_LIST_PRICE);
  }

  @Test
  void aSkuThatStartsAfterTheLaunchDoesNotCount() {
    priced("in", MeterCode.INPUT_TOKENS, "base", V1);
    priced("out", MeterCode.OUTPUT_TOKENS, "base", LAUNCH.plusSeconds(3600));
    assertThat(check(Optional.empty()).gaps())
        .extracting(Gap::meter)
        .containsExactly("output_tokens");
  }

  @Test
  void anUnregisteredModelFailsAndAFilterChecksOnlyThatModel() {
    assertThat(check(Optional.of("model-new")).gaps())
        .containsExactly(new Gap("model-new", "", "", "", Reason.NOT_REGISTERED));
    priced("in", MeterCode.INPUT_TOKENS, "base", V1);
    priced("out", MeterCode.OUTPUT_TOKENS, "base", V1);
    assertThat(check(Optional.of("model-x")).passed()).isTrue();
  }
}
