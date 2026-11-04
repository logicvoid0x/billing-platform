package com.logicvoid0x.billing.money;

/**
 * Integer money: amounts are {@code long} cents; prices are whole cents per a fixed number of
 * units. See docs/design.md, "Money representation".
 *
 * <p>Never round per request. Keep a running quantity per SKU and charge each increment with {@link
 * #charge}; the increments sum exactly to {@link #forTotal} of the final quantity in any order, so
 * every total is rounded once, half-even.
 */
public final class Cents {
  private Cents() {}

  /**
   * A price: {@code cents} for every {@code perUnits} units, e.g. $15 per million tokens = {@code
   * Rate(1500, 1_000_000)}.
   *
   * @param cents cents charged per {@code perUnits} units, {@code >= 0}
   * @param perUnits number of units the price covers, {@code > 0}
   */
  public record Rate(long cents, long perUnits) {
    /** Basis points in 100%. */
    public static final int FULL_BASIS_POINTS = 10_000;

    /** Validates the rate. */
    public Rate {
      if (cents < 0 || perUnits <= 0) {
        throw new IllegalArgumentException("rate must be cents >= 0 per units > 0");
      }
    }

    /**
     * Applies a discount in basis points (2000 = 20%); refuses a result that is not a whole number
     * of cents, in which case the SKU needs a larger {@code perUnits}.
     */
    public Rate discounted(int basisPoints) {
      if (basisPoints < 0 || basisPoints > FULL_BASIS_POINTS) {
        throw new IllegalArgumentException("discount must be 0..10000 basis points");
      }
      long numerator = Math.multiplyExact(cents, (long) FULL_BASIS_POINTS - basisPoints);
      if (numerator % FULL_BASIS_POINTS != 0) {
        throw new IllegalArgumentException(
            "discount gives a fractional rate; use a larger perUnits");
      }
      return new Rate(numerator / FULL_BASIS_POINTS, perUnits);
    }
  }

  /**
   * Cents owed for a running total of units, rounded half-even once. Split into whole blocks and a
   * remainder to avoid {@code long} overflow; overflow that cannot be avoided throws.
   */
  public static long forTotal(long units, Rate rate) {
    if (units < 0) {
      throw new IllegalArgumentException("units must be >= 0");
    }
    long whole = Math.multiplyExact(units / rate.perUnits(), rate.cents());
    long rest = Math.multiplyExact(units % rate.perUnits(), rate.cents());
    // Ties are decided by the parity of the whole result, not of rest / perUnits alone.
    return addHalfEven(whole, rest, rate.perUnits());
  }

  /**
   * Charge for moving a running total from {@code before} to {@code after}; increments sum exactly
   * to {@code forTotal(final)}.
   */
  public static long charge(long before, long after, Rate rate) {
    return forTotal(after, rate) - forTotal(before, rate);
  }

  /**
   * Hold for up to {@code maxUnits} more units on top of {@code before}, plus 1 cent because
   * concurrent requests move the running total.
   */
  public static long hold(long before, long maxUnits, Rate rate) {
    return Math.addExact(charge(before, Math.addExact(before, maxUnits), rate), 1);
  }

  /** {@code n / d} rounded half-even, for {@code n >= 0} and {@code d > 0}. */
  static long divideHalfEven(long n, long d) {
    return addHalfEven(0, n, d);
  }

  /**
   * {@code wholeCents + numerator / perUnits}, rounded half-even once as one number. Used where a
   * total is built from parts that share one {@code perUnits} (graduated tiers), so the parts are
   * summed exactly and rounded a single time.
   *
   * @param wholeCents cents already known exactly
   * @param numerator the fractional remainder, in cents times {@code perUnits}, {@code >= 0}
   * @param perUnits the denominator, {@code > 0}
   */
  public static long roundHalfEven(long wholeCents, long numerator, long perUnits) {
    if (numerator < 0 || perUnits <= 0) {
      throw new IllegalArgumentException("numerator must be >= 0 and perUnits > 0");
    }
    return addHalfEven(wholeCents, numerator, perUnits);
  }

  /**
   * {@code base + n / d} rounded half-even as one number, for {@code n >= 0} and {@code d > 0}: a
   * tie rounds to the even value of the sum.
   */
  private static long addHalfEven(long base, long n, long d) {
    long sum = Math.addExact(base, n / d);
    long remainder = n % d;
    long toNext = d - remainder; // compare remainder with d/2 without computing 2 * remainder
    if (remainder > toNext || (remainder == toNext && (sum & 1) == 1)) {
      sum = Math.addExact(sum, 1);
    }
    return sum;
  }
}
