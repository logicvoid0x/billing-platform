package com.logicvoid0x.billing.catalogclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.contracts.v1.CatalogSnapshot;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.money.Cents.Rate;
import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.ContextBandRule;
import com.logicvoid0x.billing.rating.EffectivePeriod;
import com.logicvoid0x.billing.rating.ListPrice;
import com.logicvoid0x.billing.rating.Pricing;
import com.logicvoid0x.billing.rating.RatingCatalog;
import com.logicvoid0x.billing.rating.Sku;
import com.logicvoid0x.billing.rating.Weight;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import com.logicvoid0x.billing.rating.golden.GoldenCases.GoldenCase;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CatalogSnapshotCodecTest {
  private static final Instant HOUR = GoldenCases.HOUR;

  static Stream<GoldenCase> goldenCases() {
    return GoldenCases.cases().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("goldenCases")
  void aDecodedCatalogRatesTheGoldenCasesToTheCent(GoldenCase c) {
    RatingCatalog decoded = roundTrip(GoldenCases.catalog());
    assertThat(c.chargedCents(new CatalogRatingLibrary(decoded, "test")))
        .isEqualTo(c.expectedCents());
  }

  @Test
  void theGoldenCatalogSurvivesTheWireRowForRow() {
    assertThat(roundTrip(GoldenCases.catalog())).isEqualTo(GoldenCases.catalog());
  }

  @Test
  void bandRulesWeightsAndClosedPeriodsSurviveTheWire() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant until = Instant.parse("2027-01-01T00:00:00Z");
    EffectivePeriod closed = EffectivePeriod.between(from, until);
    RatingCatalog catalog =
        new RatingCatalog(
            "catalog-v42",
            List.of(
                new Sku(
                    "sku_long",
                    "model-l/priority/input_tokens/long",
                    MeterCode.of("input_tokens"),
                    "model-l",
                    "priority",
                    "long",
                    closed)),
            List.of(new ContextBandRule("model-l", 200_000, "base", "long", closed)),
            List.of(new ListPrice("sku_long", Pricing.flat(new Rate(600, 1_000_000)), closed)),
            List.of(),
            List.of(new Weight("sku_long", new Rate(3, 1_000), closed)));
    assertThat(roundTrip(catalog)).isEqualTo(catalog);
  }

  @Test
  void theSnapshotCarriesItsHourAndVersion() {
    CatalogSnapshot wire = CatalogSnapshotCodec.encode(GoldenCases.catalog(), HOUR);
    assertThat(wire.getVersion()).isEqualTo("golden-1");
    assertThat(CatalogSnapshotCodec.hour(wire)).isEqualTo(HOUR);
  }

  @Test
  void aMissingPricingIsRejected() {
    CatalogSnapshot wire = CatalogSnapshotCodec.encode(GoldenCases.catalog(), HOUR);
    CatalogSnapshot broken =
        wire.toBuilder().setListPrices(0, wire.getListPrices(0).toBuilder().clearPricing()).build();
    assertThatThrownBy(() -> CatalogSnapshotCodec.decode(broken))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pricing");
  }

  private static RatingCatalog roundTrip(RatingCatalog catalog) {
    byte[] bytes = CatalogSnapshotCodec.encode(catalog, HOUR).toByteArray();
    try {
      return CatalogSnapshotCodec.decode(CatalogSnapshot.parseFrom(bytes));
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw new AssertionError(e);
    }
  }
}
