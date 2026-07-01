package com.logicvoid0x.billing.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.MeteredLine;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.money.Cents.Rate;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import com.logicvoid0x.billing.rating.golden.GoldenCases.GoldenCase;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The design's worked examples, to the cent. Data: src/testFixtures/resources/golden/
 * rating-golden.csv and {@link GoldenCases#catalog()}.
 */
class GoldenRatingTest {
  private final RatingLibrary rating = new CatalogRatingLibrary(GoldenCases.catalog(), "golden");

  static Stream<GoldenCase> cases() {
    List<GoldenCase> cases = GoldenCases.cases();
    assertThat(cases).hasSizeGreaterThanOrEqualTo(7);
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void incrementsSumToTheGoldenAmount(GoldenCase c) {
    assertThat(c.chargedCents(rating)).isEqualTo(c.expectedCents());
  }

  @Test
  void acmeEndToEndFromTheRawEvent() {
    // 40M output tokens in one hour: resolve, then charge on the running total.
    RequestUsage usage =
        new RequestUsage(
            "req_acme",
            "org_acme",
            "ws_1",
            "key_1",
            "model-x",
            "standard",
            "host-a",
            GoldenCases.HOUR.plusSeconds(42),
            Map.of(MeterCode.OUTPUT_TOKENS, 40_000_000L));
    List<MeteredLine> lines = rating.resolve(usage, usage.eventTime());
    assertThat(lines).hasSize(1);
    MeteredLine line = lines.get(0);
    assertThat(line.skuId()).isEqualTo("sku_out");
    assertThat(rating.rate("org_acme", line.skuId(), line.eventHour()))
        .isEqualTo(new Rate(1200, 1_000_000));
    assertThat(rating.chargeCents("org_acme", line.skuId(), 0, line.quantity(), line.eventHour()))
        .isEqualTo(48_000);
  }
}
