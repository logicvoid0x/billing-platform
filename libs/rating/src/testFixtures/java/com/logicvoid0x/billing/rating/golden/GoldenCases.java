package com.logicvoid0x.billing.rating.golden;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.money.Cents.Rate;
import com.logicvoid0x.billing.rating.ContractPrice;
import com.logicvoid0x.billing.rating.EffectivePeriod;
import com.logicvoid0x.billing.rating.ListPrice;
import com.logicvoid0x.billing.rating.Pricing;
import com.logicvoid0x.billing.rating.RatingCatalog;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.Sku;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The design's worked rating examples, to the cent: the golden catalog and the cases in {@code
 * golden/rating-golden.csv}. Shared so every place that builds a catalog (in memory, or loaded from
 * the catalog service's database) is checked against the same numbers.
 */
public final class GoldenCases {
  /** Start of every golden row's effective period. */
  public static final Instant V1 = Instant.parse("2026-01-01T00:00:00Z");

  /** The hour the golden cases are rated in. */
  public static final Instant HOUR = Instant.parse("2026-11-01T10:00:00Z");

  private static final long BILLION = 1_000_000_000L;

  private GoldenCases() {}

  /** One golden case: increments charged on a running total must sum to {@code expectedCents}. */
  public record GoldenCase(
      String name, String org, String sku, long before, List<Long> increments, long expectedCents) {
    /** Charges every increment on the running total with {@code rating} at {@link #HOUR}. */
    public long chargedCents(RatingLibrary rating) {
      long total = before;
      long charged = 0;
      for (long increment : increments) {
        charged =
            Math.addExact(
                charged,
                rating.chargeCents(org, sku, total, Math.addExact(total, increment), HOUR));
        total = Math.addExact(total, increment);
      }
      return charged;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  /** Sonnet-style output SKU at $15/M list; Acme has 20% off; a tiered SKU for the tier example. */
  public static RatingCatalog catalog() {
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

  /** Every case in {@code golden/rating-golden.csv}. */
  public static List<GoldenCase> cases() {
    List<GoldenCase> cases = new ArrayList<>();
    try (InputStream in =
            Objects.requireNonNull(
                GoldenCases.class.getResourceAsStream("/golden/rating-golden.csv"),
                "golden/rating-golden.csv");
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
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return List.copyOf(cases);
  }
}
