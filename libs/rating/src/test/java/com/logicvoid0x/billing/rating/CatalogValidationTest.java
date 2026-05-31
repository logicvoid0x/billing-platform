package com.logicvoid0x.billing.rating;

import static com.logicvoid0x.billing.rating.Fixtures.FIFTEEN_PER_M;
import static com.logicvoid0x.billing.rating.Fixtures.V1;
import static com.logicvoid0x.billing.rating.Fixtures.V2;
import static com.logicvoid0x.billing.rating.Fixtures.sku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.domain.MeterCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogValidationTest {
  @Test
  void overlappingListPricesAreRejected() {
    assertThatThrownBy(() ->
            new RatingCatalog(
                "c",
                List.of(sku("sku_out", MeterCode.OUTPUT_TOKENS, "base")),
                List.of(),
                List.of(
                    new ListPrice("sku_out", Pricing.flat(FIFTEEN_PER_M), EffectivePeriod.from(V1)),
                    new ListPrice(
                        "sku_out", Pricing.flat(FIFTEEN_PER_M), EffectivePeriod.from(V2))),
                List.of(),
                List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overlap");
  }

  @Test
  void pricesMustStartOnHourBoundaries() {
    assertThatThrownBy(() ->
            new ListPrice(
                "sku_out", Pricing.flat(FIFTEEN_PER_M), EffectivePeriod.from(V1.plusSeconds(60))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hour");
  }

  @Test
  void pricesMustReferToKnownSkus() {
    assertThatThrownBy(() ->
            new RatingCatalog(
                "c",
                List.of(),
                List.of(),
                List.of(
                    new ListPrice("nope", Pricing.flat(FIFTEEN_PER_M), EffectivePeriod.from(V1))),
                List.of(),
                List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope");
  }

  @Test
  void duplicateSkuIdsAreRejected() {
    assertThatThrownBy(() ->
            new RatingCatalog(
                "c",
                List.of(
                    sku("sku_out", MeterCode.OUTPUT_TOKENS, "base"),
                    sku("sku_out", MeterCode.OUTPUT_TOKENS, "long")),
                List.of(),
                List.of(),
                List.of(),
                List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void effectivePeriodIsHalfOpen() {
    EffectivePeriod p = EffectivePeriod.between(V1, V2);
    assertThat(p.contains(V1)).isTrue();
    assertThat(p.contains(V2.minusNanos(1))).isTrue();
    assertThat(p.contains(V2)).isFalse();
    assertThat(p.contains(V1.minusNanos(1))).isFalse();
    assertThat(EffectivePeriod.from(V1).contains(Instant.parse("9999-01-01T00:00:00Z"))).isTrue();
    assertThat(p.overlaps(EffectivePeriod.from(V2))).isFalse();
    assertThat(p.overlaps(EffectivePeriod.from(V2.minusSeconds(3_600)))).isTrue();
    assertThatThrownBy(() -> EffectivePeriod.between(V2, V1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
