package com.logicvoid0x.billing.balanceledger;

import com.logicvoid0x.billing.balanceledger.BalanceLedger.Entry;
import com.logicvoid0x.billing.money.Cents;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The home balance ledger's escrow to regions (design, Stage 2: the home ledger "grants each region
 * an escrow sized at about 30 s of regional burn"; "when an account nears $0 the home region stops
 * granting new escrow"; regions return idle escrow "after 5 min idle").
 *
 * <p>A grant debits customer credit, so spendable credit is already net of outstanding escrow. A
 * grant is made in full or not at all: it is refused when spendable credit would fall below the
 * near-zero reserve, so near $0 the region falls back to direct calls to the home region and
 * precision is bought only there. Every write is keyed by business identity: grants by the region's
 * request sequence, returns by its return sequence, so retries are no-ops.
 */
public final class EscrowGranter {
  /** Regional runway an escrow grant covers. */
  public static final Duration GRANT_RUNWAY = Duration.ofSeconds(30);

  /** Escrow unused for this long goes back to the account. */
  public static final Duration IDLE_RETURN = Duration.ofMinutes(5);

  /** Outcome of an escrow request. */
  public record Grant(long cents, boolean nearZero) {}

  private final BalanceLedger ledger;
  private final long nearZeroReserveCents;
  private final long minGrantCents;

  /**
   * Creates the granter.
   *
   * @param ledger the home balance ledger
   * @param nearZeroReserveCents spendable credit that stays home (no grant may dip below it)
   * @param minGrantCents smallest grant worth making (at least the region's smallest hold)
   */
  public EscrowGranter(BalanceLedger ledger, long nearZeroReserveCents, long minGrantCents) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    if (nearZeroReserveCents < 0 || minGrantCents < 1) {
      throw new IllegalArgumentException("reserve must be >= 0 and the smallest grant >= 1 cent");
    }
    this.nearZeroReserveCents = nearZeroReserveCents;
    this.minGrantCents = minGrantCents;
  }

  /** The grant size for a regional burn rate: 30 s of burn, rounded up, at least the minimum. */
  public long grantSize(Cents.Rate burnPerSecond) {
    long cents =
        ceilDiv(
            Math.multiplyExact(burnPerSecond.cents(), GRANT_RUNWAY.toSeconds()),
            burnPerSecond.perUnits());
    return Math.max(cents, minGrantCents);
  }

  /**
   * Handles a region's escrow request.
   *
   * @param requestSeq the region's sequence for this request; a retry reuses it
   * @return the cents granted (0 when refused near $0)
   */
  public Grant request(
      String org, String region, Cents.Rate burnPerSecond, long requestSeq, Instant now)
      throws SQLException {
    String key = org + ":" + region + ":escrow_grant:" + requestSeq;
    java.util.OptionalLong done = ledger.amountOf(key);
    if (done.isPresent()) {
      return new Grant(done.getAsLong(), false); // a retry gets the original grant
    }
    long size = grantSize(burnPerSecond);
    BalanceLedger.Outcome outcome =
        ledger.post(
            new Entry(key, EntryType.ESCROW_GRANT, org, region, size, now), nearZeroReserveCents);
    if (outcome == BalanceLedger.Outcome.REFUSED) {
      return new Grant(0, true);
    }
    return new Grant(ledger.amountOf(key).orElse(size), false);
  }

  /** True when a region's escrow has been idle long enough to go back. */
  public static boolean idle(Instant lastUsedAt, Instant now) {
    return !now.isBefore(lastUsedAt.plus(IDLE_RETURN));
  }

  /**
   * Takes back a region's unused escrow. The region rolls its usage up first and reports what is
   * left; the return is capped at the escrow the home ledger still holds for the region.
   *
   * @param returnSeq the region's sequence for this return; a retry reuses it
   * @return cents returned to customer credit
   */
  public long returnUnused(
      String org, String region, long unusedCents, long returnSeq, Instant lastUsedAt, Instant now)
      throws SQLException {
    String key = org + ":" + region + ":escrow_return:" + returnSeq;
    java.util.OptionalLong done = ledger.amountOf(key);
    if (done.isPresent()) {
      return done.getAsLong(); // a retry gets the original return
    }
    if (!idle(lastUsedAt, now) || unusedCents <= 0) {
      return 0;
    }
    long held = ledger.balance(AccountKind.ESCROW.accountId(org, region));
    long cents = Math.min(unusedCents, held);
    if (cents <= 0) {
      return 0;
    }
    ledger.post(new Entry(key, EntryType.ESCROW_RETURN, org, region, cents, now));
    return cents;
  }

  private static long ceilDiv(long a, long b) {
    return Math.floorDiv(Math.addExact(a, b - 1), b);
  }
}
