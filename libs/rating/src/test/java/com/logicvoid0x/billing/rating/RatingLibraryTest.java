package com.logicvoid0x.billing.rating;

import static com.logicvoid0x.billing.rating.Fixtures.V1;
import static com.logicvoid0x.billing.rating.Fixtures.V2;
import static com.logicvoid0x.billing.rating.Fixtures.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.MeteredLine;
import com.logicvoid0x.billing.money.Cents.Rate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RatingLibraryTest {
  private static final Instant HOUR = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant AT = HOUR.plusSeconds(125);
  private final RatingLibrary rating = new CatalogRatingLibrary(Fixtures.catalog(), "rating-1");

  @Test
  void resolvesOneLinePerNonZeroMeterInTheBaseBand() {
    List<MeteredLine> lines =
        rating.resolve(
            usage(
                AT,
                Map.of(
                    MeterCode.INPUT_TOKENS, 1_000L,
                    MeterCode.OUTPUT_TOKENS, 500L,
                    MeterCode.CACHE_READ_TOKENS, 0L)),
            AT);
    assertThat(lines)
        .extracting(MeteredLine::skuId, MeteredLine::quantity)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("sku_in", 1_000L),
            org.assertj.core.groups.Tuple.tuple("sku_out", 500L));
    assertThat(lines).allSatisfy(l -> assertThat(l.requestId()).isEqualTo("req_1"));
  }

  @Test
  void promptAboveTheThresholdUsesTheLongContextBand() {
    // Prompt = input + cache read + cache write = 150k + 60k = 210k > 200k.
    List<MeteredLine> lines =
        rating.resolve(
            usage(
                AT,
                Map.of(
                    MeterCode.INPUT_TOKENS, 150_000L,
                    MeterCode.CACHE_READ_TOKENS, 60_000L,
                    MeterCode.OUTPUT_TOKENS, 10L)),
            AT);
    assertThat(lines)
        .extracting(MeteredLine::skuId)
        .containsExactly("sku_cache_read_long", "sku_in_long", "sku_out_long");
  }

  @Test
  void promptAtTheThresholdStaysInTheBaseBand() {
    List<MeteredLine> lines =
        rating.resolve(usage(AT, Map.of(MeterCode.INPUT_TOKENS, 200_000L)), AT);
    assertThat(lines).extracting(MeteredLine::skuId).containsExactly("sku_in");
  }

  @Test
  void unknownSkuIsReportedNotGuessed() {
    // There is no cache-write SKU in the fixture catalog.
    assertThatThrownBy(
            () -> rating.resolve(usage(AT, Map.of(MeterCode.CACHE_WRITE_TOKENS, 10L)), AT))
        .isInstanceOf(RatingException.class)
        .hasMessageContaining("cache_write_tokens");
    assertThatThrownBy(
            () -> rating.resolve(usage(AT, Map.of(MeterCode.INPUT_TOKENS, 1L)), V1.minusSeconds(1)))
        .as("before the SKU is effective")
        .isInstanceOf(RatingException.class);
  }

  @Test
  void contractOverrideWinsOverListPrice() {
    assertThat(rating.rate("org_acme", "sku_out", HOUR)).isEqualTo(new Rate(1200, 1_000_000));
    assertThat(rating.rate("org_other", "sku_out", HOUR)).isEqualTo(new Rate(1500, 1_000_000));
  }

  @Test
  void listPriceIsEffectiveDatedOnHourBoundaries() {
    assertThat(rating.rate("org_other", "sku_out", V2.minusSeconds(3_600)))
        .isEqualTo(new Rate(1500, 1_000_000));
    assertThat(rating.rate("org_other", "sku_out", V2)).isEqualTo(new Rate(1800, 1_000_000));
    assertThatThrownBy(() -> rating.rate("org_other", "sku_out", HOUR.plusSeconds(1)))
        .as("rating takes an hour, not an instant inside one")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void chargeIsTheDifferenceOfRunningTotals() {
    // Acme: 40M output tokens at 20% off $15/M = 48,000 cents, in any split.
    long first = rating.chargeCents("org_acme", "sku_out", 0, 25_000_000, HOUR);
    long second = rating.chargeCents("org_acme", "sku_out", 25_000_000, 40_000_000, HOUR);
    assertThat(first + second).isEqualTo(48_000);
  }

  @Test
  void usageUnitsComeFromTheWeightBook() {
    assertThat(rating.usageUnits("sku_out", 1_000, AT)).isEqualTo(5_000);
    assertThat(rating.usageUnits("sku_in", 1_000, AT)).isEqualTo(1_000);
    assertThat(rating.usageUnits("sku_cache_read", 1_005, AT)).isEqualTo(100); // 100.5 -> 100
    assertThatThrownBy(() -> rating.usageUnits("sku_in_long", 1, AT))
        .isInstanceOf(RatingException.class);
  }

  @Test
  void versionIsRecordedOnEveryRow() {
    assertThat(rating.version()).isEqualTo("rating-1/catalog-1");
  }
}
