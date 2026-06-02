package com.logicvoid0x.billing.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.MeteredLine;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.money.Cents.Rate;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The design's worked examples, to the cent. Data: src/test/resources/golden/rating-golden.csv. */
class GoldenRatingTest {
  private static final Instant V1 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant HOUR = Instant.parse("2026-11-01T10:00:00Z");
  private static final long BILLION = 1_000_000_000L;

  /** Sonnet-style output SKU at $15/M list; Acme has 20% off; a tiered SKU for the tier example. */
  static RatingCatalog goldenCatalog() {
    EffectivePeriod always = EffectivePeriod.from(V1);
    Pricing list = Pricing.flat(new Rate(1500, 1_000_000));
    return new RatingCatalog(
        "golden-1",
        List.of(
            new Sku(
                "sku_out",
                "model-x/standard/output_tokens/base",
                MeterCode.OUTPUT_TOKENS,
                "model-x",
                "standard",
                "base",
                always),
            new Sku(
                "sku_tiered",
                "model-t/standard/output_tokens/base",
                MeterCode.OUTPUT_TOKENS,
                "model-t",
                "standard",
                "base",
                always)),
        List.of(),
        List.of(
            new ListPrice("sku_out", list, always),
            new ListPrice(
                "sku_tiered",
                // Assumed table, see docs/decisions/open-questions.md Q3.
                Pricing.graduated(
                    List.of(
                        new Pricing.Tier(BILLION, new Rate(1500, 1_000_000)),
                        new Pricing.Tier(Long.MAX_VALUE, new Rate(1200, 1_000_000)))),
                always)),
        List.of(ContractPrice.discount("org_acme", "sku_out", list, 2000, always)),
        List.of());
  }

  private final RatingLibrary rating = new CatalogRatingLibrary(goldenCatalog(), "golden");

  record GoldenCase(
      String name, String org, String sku, long before, List<Long> increments, long expectedCents) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<GoldenCase> cases() throws IOException {
    List<GoldenCase> cases = new ArrayList<>();
    try (InputStream in =
            Objects.requireNonNull(
                GoldenRatingTest.class.getResourceAsStream("/golden/rating-golden.csv"));
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] f = line.split(",", -1);
        cases.add(
            new GoldenCase(
                f[0],
                f[1],
                f[2],
                Long.parseLong(f[3]),
                Arrays.stream(f[4].split(";")).map(Long::valueOf).toList(),
                Long.parseLong(f[5])));
      }
    }
    assertThat(cases).hasSizeGreaterThanOrEqualTo(7);
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void incrementsSumToTheGoldenAmount(GoldenCase c) {
    long total = c.before();
    long charged = 0;
    for (long increment : c.increments()) {
      charged += rating.chargeCents(c.org(), c.sku(), total, total + increment, HOUR);
      total += increment;
    }
    assertThat(charged).isEqualTo(c.expectedCents());
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
            HOUR.plusSeconds(42),
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
