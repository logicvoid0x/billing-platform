package com.logicvoid0x.billing.money;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.money.Cents.Rate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

class CentsProperties {
  private static final long TEN_TRILLION = 10_000_000_000_000L;

  @Provide
  Arbitrary<Rate> rates() {
    Arbitrary<Long> cents = Arbitraries.longs().between(0, 100_000);
    Arbitrary<Long> perUnits =
        Arbitraries.oneOf(
            Arbitraries.of(1L, 2L, 3L, 1_000L, 1_000_000L, 1_000_000_000L),
            Arbitraries.longs().between(1, 1_000_000_000L));
    return Combinators.combine(cents, perUnits).as(Rate::new);
  }

  @Property(tries = 500)
  void incrementsSumToForTotalInAnyOrder(
      @ForAll("rates") Rate rate,
      @ForAll @Size(min = 1, max = 300) List<@LongRange(min = 0, max = 50_000_000) Long> sizes,
      @ForAll long seed) {
    long expected = Cents.forTotal(sizes.stream().mapToLong(Long::longValue).sum(), rate);
    assertThat(chargeAll(sizes, rate)).isEqualTo(expected);

    List<Long> shuffled = new ArrayList<>(sizes);
    Collections.shuffle(shuffled, new Random(seed));
    assertThat(chargeAll(shuffled, rate)).isEqualTo(expected);
  }

  @Property(tries = 500)
  void noOverflowUpToTenTrillionUnits(
      @ForAll("rates") Rate rate,
      @ForAll @LongRange(min = 0, max = TEN_TRILLION) long a,
      @ForAll @LongRange(min = 0, max = TEN_TRILLION) long b) {
    long lo = Math.min(a, b);
    long hi = Math.max(a, b);
    long fLo = Cents.forTotal(lo, rate);
    long fHi = Cents.forTotal(hi, rate);
    assertThat(fHi).isGreaterThanOrEqualTo(fLo).isGreaterThanOrEqualTo(0);
    // Rounded once: splitting a total changes the result by at most one cent.
    long split = Cents.forTotal(lo, rate) + Cents.forTotal(hi - lo, rate);
    assertThat(Math.abs(fHi - split)).isLessThanOrEqualTo(1);
    assertThat(Cents.charge(lo, hi, rate)).isEqualTo(fHi - fLo);
  }

  @Property(tries = 1000)
  void roundsToWithinHalfAUnitOfTheExactValue(
      @ForAll @LongRange(min = 0, max = 1_000_000_000L) long units,
      @ForAll @LongRange(min = 0, max = 100_000) long cents,
      @ForAll @LongRange(min = 1, max = 1_000_000_000L) long perUnits) {
    long result = Cents.forTotal(units, new Rate(cents, perUnits));
    // units * cents <= 1e14, so the exact numerator fits in a long here.
    long error = Math.abs(Math.subtractExact(Math.multiplyExact(result, perUnits), units * cents));
    assertThat(2 * error).isLessThanOrEqualTo(perUnits);
    if (2 * error == perUnits) {
      assertThat(result % 2).as("ties go to even").isZero();
    }
  }

  @Property(tries = 300)
  void holdCoversTheChargeOfTheMaximumIncrement(
      @ForAll("rates") Rate rate,
      @ForAll @LongRange(min = 0, max = TEN_TRILLION) long before,
      @ForAll @LongRange(min = 0, max = 10_000_000) long maxUnits,
      @ForAll @LongRange(min = 0, max = 10_000_000) long actualUnits) {
    long actual = Math.min(actualUnits, maxUnits);
    assertThat(Cents.hold(before, maxUnits, rate))
        .isGreaterThan(Cents.charge(before, before + actual, rate));
  }

  private static long chargeAll(List<Long> sizes, Rate rate) {
    long total = 0;
    long charged = 0;
    for (long size : sizes) {
      charged += Cents.charge(total, total + size, rate);
      total += size;
    }
    return charged;
  }
}
