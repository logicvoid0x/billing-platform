package com.logicvoid0x.billing.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.money.Cents.Rate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

class TierProperties {
  private static final long TEN_TRILLION = 10_000_000_000_000L;

  /** Two to five graduated tiers with ascending bounds and one shared perUnits. */
  @Provide
  Arbitrary<Pricing> graduated() {
    Arbitrary<Long> perUnits = Arbitraries.of(1L, 7L, 1_000L, 1_000_000L, 1_000_000_000L);
    Arbitrary<List<Long>> bounds =
        Arbitraries.longs()
            .between(1, 5_000_000_000L)
            .set()
            .ofMinSize(1)
            .ofMaxSize(4)
            .map(s -> new ArrayList<>(new TreeSet<>(s)));
    Arbitrary<List<Long>> cents = Arbitraries.longs().between(0, 10_000).list().ofSize(5);
    return Combinators.combine(perUnits, bounds, cents)
        .as((p, b, c) -> {
          List<Pricing.Tier> tiers = new ArrayList<>();
          for (int i = 0; i < b.size(); i++) {
            tiers.add(new Pricing.Tier(b.get(i), new Rate(c.get(i), p)));
          }
          tiers.add(new Pricing.Tier(Long.MAX_VALUE, new Rate(c.get(b.size()), p)));
          return Pricing.graduated(tiers);
        });
  }

  @Property(tries = 500)
  void tierChargesTelescopeToFOfTheFinalTotalInAnyOrder(
      @ForAll("graduated") Pricing pricing,
      @ForAll @LongRange(min = 0, max = 2_000_000_000L) long start,
      @ForAll @Size(min = 1, max = 200) List<@LongRange(min = 0, max = 100_000_000) Long> sizes,
      @ForAll long seed) {
    // Tier true-up at close is zero: the charges equal F(end) - F(start) exactly, in any order.
    long end = start + sizes.stream().mapToLong(Long::longValue).sum();
    long expected = pricing.totalCents(end) - pricing.totalCents(start);
    assertThat(chargeAll(pricing, start, sizes)).isEqualTo(expected);

    List<Long> shuffled = new ArrayList<>(sizes);
    Collections.shuffle(shuffled, new Random(seed));
    assertThat(chargeAll(pricing, start, shuffled)).isEqualTo(expected);
  }

  @Property(tries = 500)
  void fIsMonotoneAndDoesNotOverflowToTenTrillion(
      @ForAll("graduated") Pricing pricing,
      @ForAll @LongRange(min = 0, max = TEN_TRILLION) long a,
      @ForAll @LongRange(min = 0, max = TEN_TRILLION) long b) {
    long lo = Math.min(a, b);
    long hi = Math.max(a, b);
    assertThat(pricing.totalCents(hi)).isGreaterThanOrEqualTo(pricing.totalCents(lo));
    assertThat(pricing.charge(lo, hi)).isGreaterThanOrEqualTo(0);
  }

  @Property(tries = 300)
  void discountNeverRaisesTheCharge(
      @ForAll @LongRange(min = 0, max = 10_000) long cents,
      @ForAll @LongRange(min = 0, max = 100) long tenths,
      @ForAll @LongRange(min = 0, max = TEN_TRILLION) long units) {
    // Rates that are multiples of 100 cents accept any whole-percent discount.
    Pricing list = Pricing.flat(new Rate(cents * 100, 1_000_000_000L));
    Pricing discounted = list.discounted((int) (tenths * 100));
    assertThat(discounted.totalCents(units)).isLessThanOrEqualTo(list.totalCents(units));
  }

  private static long chargeAll(Pricing pricing, long start, List<Long> sizes) {
    long total = start;
    long charged = 0;
    for (long size : sizes) {
      charged += pricing.charge(total, total + size);
      total += size;
    }
    return charged;
  }
}
