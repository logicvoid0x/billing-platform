package com.logicvoid0x.billing.rating;

import com.logicvoid0x.billing.money.Cents;
import com.logicvoid0x.billing.money.Cents.Rate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How a SKU is priced, as {@code F(total)} in cents of a running quantity. Charges are always
 * {@code F(after) - F(before)}, so they telescope to {@code F(final)} in any arrival order.
 */
public sealed interface Pricing permits Pricing.Flat, Pricing.Graduated {

  /** {@code F(units)}: cents owed for a running total, rounded half-even once. */
  long totalCents(long units);

  /** The same pricing with a basis-point discount applied to every rate. */
  Pricing discounted(int basisPoints);

  /** The rate when the pricing is flat; empty for graduated tiers. */
  Optional<Rate> flatRate();

  /** Charge for moving a running total from {@code before} to {@code after}. */
  default long charge(long before, long after) {
    if (after < before) {
      throw new IllegalArgumentException("running total cannot shrink");
    }
    return totalCents(after) - totalCents(before);
  }

  /** One rate for every unit. */
  static Pricing flat(Rate rate) {
    return new Flat(rate);
  }

  /** Graduated tiers; see {@link Graduated}. */
  static Pricing graduated(List<Tier> tiers) {
    return new Graduated(tiers);
  }

  /**
   * One graduated tier: units up to {@code upTo} (exclusive cumulative bound) at {@code rate}.
   *
   * @param upTo cumulative bound, exclusive; {@link Long#MAX_VALUE} for the open-ended last tier
   * @param rate the rate for units in this tier
   */
  record Tier(long upTo, Rate rate) {
    /** Validates the tier. */
    public Tier {
      Objects.requireNonNull(rate, "rate");
      if (upTo <= 0) {
        throw new IllegalArgumentException("upTo must be > 0");
      }
    }
  }

  /**
   * Flat pricing.
   *
   * @param rate the rate
   */
  record Flat(Rate rate) implements Pricing {
    /** Validates the pricing. */
    public Flat {
      Objects.requireNonNull(rate, "rate");
    }

    @Override
    public long totalCents(long units) {
      return Cents.forTotal(units, rate);
    }

    @Override
    public Pricing discounted(int basisPoints) {
      return new Flat(rate.discounted(basisPoints));
    }

    @Override
    public Optional<Rate> flatRate() {
      return Optional.of(rate);
    }
  }

  /**
   * Graduated tiers: each portion of the running total is charged at its tier rate. All tiers share
   * one {@code perUnits}, so the portions are summed exactly and {@code F(total)} is rounded once.
   *
   * @param tiers in ascending {@code upTo} order; the last is open-ended
   */
  record Graduated(List<Tier> tiers) implements Pricing {
    /** Validates the tiers and copies them. */
    public Graduated {
      tiers = List.copyOf(tiers);
      if (tiers.isEmpty()) {
        throw new IllegalArgumentException("at least one tier is required");
      }
      long perUnits = tiers.get(0).rate().perUnits();
      long previous = 0;
      for (Tier t : tiers) {
        if (t.upTo() <= previous) {
          throw new IllegalArgumentException("tiers must have ascending upTo bounds");
        }
        if (t.rate().perUnits() != perUnits) {
          throw new IllegalArgumentException("all tiers must share one perUnits");
        }
        previous = t.upTo();
      }
      if (previous != Long.MAX_VALUE) {
        throw new IllegalArgumentException("the last tier must be open-ended (Long.MAX_VALUE)");
      }
    }

    @Override
    public long totalCents(long units) {
      if (units < 0) {
        throw new IllegalArgumentException("units must be >= 0");
      }
      long perUnits = tiers.get(0).rate().perUnits();
      long whole = 0;
      long remainder = 0;
      long lower = 0;
      for (Tier t : tiers) {
        if (units <= lower) {
          break;
        }
        long inTier = Math.min(units, t.upTo()) - lower;
        long cents = t.rate().cents();
        whole = Math.addExact(whole, Math.multiplyExact(inTier / perUnits, cents));
        remainder = Math.addExact(remainder, Math.multiplyExact(inTier % perUnits, cents));
        lower = t.upTo();
      }
      return Cents.roundHalfEven(whole, remainder, perUnits);
    }

    @Override
    public Pricing discounted(int basisPoints) {
      return new Graduated(
          tiers.stream().map(t -> new Tier(t.upTo(), t.rate().discounted(basisPoints))).toList());
    }

    @Override
    public Optional<Rate> flatRate() {
      return Optional.empty();
    }
  }
}
