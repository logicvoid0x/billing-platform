package com.logicvoid0x.billing.invoicing;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The commitment shortfall line (Q8 follow-up): an org with a committed-use commitment pays the
 * unused part of it, {@code commit_cents - sum(drawdown_cents)} for the period, when positive.
 * Overage is already on the usage lines at the same flat rate, so a covered commitment adds
 * nothing.
 *
 * <p>The line is an invoice item under the pseudo-SKU {@link #SKU}, keyed like every line by
 * hash(org, SKU, period, ledger version). A re-close at the same version pushes nothing new; a
 * correction (a new ledger version) pushes only the difference from what was already pushed for the
 * period, which can be negative. Pushed lines are never changed.
 */
public final class CommitmentShortfall {
  /** Pseudo-SKU of shortfall lines; a permanent string. */
  public static final String SKU = "commit-shortfall";

  private CommitmentShortfall() {}

  /**
   * The unused part of a commitment.
   *
   * @param commitCents the period's commitment, > 0
   * @param drawdownCents usage the commitment covered in the period, 0..commitCents
   */
  public static long amount(long commitCents, long drawdownCents) {
    if (commitCents <= 0) {
      throw new IllegalArgumentException("commitment must be > 0 cents: " + commitCents);
    }
    if (drawdownCents < 0 || drawdownCents > commitCents) {
      throw new IllegalArgumentException(
          "drawdown " + drawdownCents + " outside 0.." + commitCents + " cents");
    }
    return commitCents - drawdownCents;
  }

  /**
   * Closes the shortfall of one org and period: pushes the difference between the shortfall now and
   * what was already pushed for the period.
   *
   * @param ledgerVersion the rated ledger version the drawdown was read at (>= 1)
   * @param now when close runs; after the period's freeze a change is a prior-period adjustment
   * @return the line pushed now, or empty if nothing changed
   */
  public static Optional<LinePusher.Line> close(
      LinePusher pusher,
      String orgId,
      Instant periodStart,
      long commitCents,
      long drawdownCents,
      int ledgerVersion,
      Instant now)
      throws SQLException {
    Objects.requireNonNull(pusher, "pusher");
    Objects.requireNonNull(orgId, "orgId");
    Instant start = PeriodClose.periodStart(periodStart);
    if (!start.equals(periodStart)) {
      throw new IllegalArgumentException("not a period start: " + periodStart);
    }
    long shortfall = amount(commitCents, drawdownCents);
    if (pusher.pushed(orgId, SKU, start, ledgerVersion).isPresent()) {
      return Optional.empty(); // this version was closed before
    }
    long delta = shortfall - pusher.pushedTotal(orgId, SKU, start);
    if (delta == 0) {
      return Optional.empty();
    }
    PeriodClose.Placement placement = PeriodClose.place(start, delta, now);
    String description =
        placement.priorPeriodAdjustment()
            ? "Commitment shortfall, prior-period adjustment for " + start + ": " + delta + " cents"
            : "Commitment shortfall: "
                + shortfall
                + " of "
                + commitCents
                + " cents committed was not used";
    LinePusher.Line line =
        new LinePusher.Line(orgId, SKU, start, ledgerVersion, delta, description);
    pusher.push(line);
    return Optional.of(line);
  }
}
