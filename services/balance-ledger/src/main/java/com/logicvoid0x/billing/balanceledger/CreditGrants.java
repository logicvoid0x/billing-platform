package com.logicvoid0x.billing.balanceledger;

import com.logicvoid0x.billing.balanceledger.BalanceLedger.Entry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Credit grants as sub-balances of customer credit (design: "sub-balances with an expiry, drawn
 * down expiring-first; expiry posts GRANT_EXPIRY to breakage revenue"). Assumptions in design Q9.
 *
 * <p>Lots are derived from the ledger, not stored per posting. Customer-credit postings are
 * replayed in time order: a grant's funding TOP_UP opens its lot, and any other TOP_UP adds to the
 * cash lot, which never expires. A debit draws the live lot that expires first, and cash last (cash
 * may go negative within the overdraft). A GRANT_EXPIRY drains its own lot. A credit coming back
 * (escrow return, negative true-up) undoes the most recent draws first, into the cash lot if the
 * drawn lot has expired since.
 */
public final class CreditGrants {
  /** The cash lot's ID. */
  public static final String CASH = "cash";

  /** Where a lot stands at a point in time. */
  public record Lot(String id, Instant expiresAt, long granted, long remaining) {}

  private final DataSource dataSource;
  private final BalanceLedger ledger;

  /** Creates the view over the ledger. */
  public CreditGrants(DataSource dataSource, BalanceLedger ledger) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
  }

  /** Grants credit that expires at {@code expiresAt}. Idempotent on {@code grantId}. */
  public void grant(
      String grantId, String org, long cents, Instant grantedAt, Instant expiresAt, String source)
      throws SQLException {
    String key = "grant:" + grantId;
    ledger.post(new Entry(key, EntryType.TOP_UP, org, null, cents, grantedAt));
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO balance_ledger.credit_grant (grant_id, org_id, cents, granted_at,"
                    + " expires_at, source, funding_entry_key) VALUES (?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (grant_id) DO NOTHING")) {
      s.setString(1, grantId);
      s.setString(2, org);
      s.setLong(3, cents);
      s.setTimestamp(4, Timestamp.from(grantedAt));
      s.setTimestamp(5, Timestamp.from(expiresAt));
      s.setString(6, source);
      s.setString(7, key);
      s.executeUpdate();
    }
  }

  /**
   * Posts GRANT_EXPIRY for every grant of {@code org} that expired by {@code now}, for whatever was
   * left at its expiry. Idempotent (key grant:<id>:expiry).
   *
   * @return cents moved to breakage now
   */
  public long expireDue(String org, Instant now) throws SQLException {
    long expired = 0;
    for (GrantRow g : grants(org)) {
      if (g.expiresAt().isAfter(now)) {
        continue;
      }
      String key = "grant:" + g.id() + ":expiry";
      if (ledger.amountOf(key).isPresent()) {
        continue;
      }
      long left =
          lotsBefore(org, g.expiresAt()).stream()
              .filter(l -> l.id().equals(g.id()))
              .mapToLong(Lot::remaining)
              .sum();
      if (left > 0) {
        ledger.post(new Entry(key, EntryType.GRANT_EXPIRY, org, null, left, g.expiresAt()));
        expired = Math.addExact(expired, left);
      }
    }
    return expired;
  }

  private record GrantRow(String id, Instant expiresAt, String fundingKey) {}

  private List<GrantRow> grants(String org) throws SQLException {
    List<GrantRow> out = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT grant_id, expires_at, funding_entry_key FROM balance_ledger.credit_grant"
                    + " WHERE org_id = ? ORDER BY expires_at, grant_id")) {
      s.setString(1, org);
      try (ResultSet rs = s.executeQuery()) {
        while (rs.next()) {
          out.add(new GrantRow(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3)));
        }
      }
    }
    return out;
  }

  /** A lot being replayed. */
  private static final class Live {
    final String id;
    final Instant expiresAt; // null for cash
    long granted;
    long remaining;

    Live(String id, Instant expiresAt) {
      this.id = id;
      this.expiresAt = expiresAt;
    }

    boolean liveAt(Instant t) {
      return expiresAt == null || t.isBefore(expiresAt);
    }
  }

  /** One draw, kept so a credit coming back can undo it. */
  private record Draw(Live lot, long cents) {}

  /**
   * The lots of {@code org} after replaying every customer-credit posting that occurred at or
   * before {@code asOf}, grants first by expiry, cash last. Their remainders sum to spendable
   * credit at that time.
   */
  public List<Lot> lots(String org, Instant asOf) throws SQLException {
    return replay(org, asOf, true);
  }

  /**
   * As {@link #lots}, but only postings strictly before {@code t} (what a grant held at expiry).
   */
  List<Lot> lotsBefore(String org, Instant t) throws SQLException {
    return replay(org, t, false);
  }

  private List<Lot> replay(String org, Instant asOf, boolean inclusive) throws SQLException {
    Map<String, Live> byFundingKey = new LinkedHashMap<>();
    Map<String, Live> byGrant = new LinkedHashMap<>();
    for (GrantRow g : grants(org)) {
      Live l = new Live(g.id(), g.expiresAt());
      byFundingKey.put(g.fundingKey(), l);
      byGrant.put(g.id(), l);
    }
    Live cash = new Live(CASH, null);
    List<Live> drawOrder = new ArrayList<>(byGrant.values());
    drawOrder.sort(Comparator.comparing((Live l) -> l.expiresAt).thenComparing(l -> l.id));
    drawOrder.add(cash);
    Deque<Draw> draws = new ArrayDeque<>();

    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT e.entry_key, e.entry_type, e.occurred_at, p.debit_cents, p.credit_cents"
                    + " FROM balance_ledger.posting p JOIN balance_ledger.entry e USING (entry_key)"
                    + " WHERE p.account_id = ? AND e.occurred_at "
                    + (inclusive ? "<=" : "<")
                    + " ?"
                    + " ORDER BY e.occurred_at, e.created_at, e.entry_key")) {
      s.setString(1, AccountKind.CUSTOMER_CREDIT.accountId(org, null));
      s.setTimestamp(2, Timestamp.from(asOf));
      try (ResultSet rs = s.executeQuery()) {
        while (rs.next()) {
          String key = rs.getString(1);
          String type = rs.getString(2);
          Instant at = rs.getTimestamp(3).toInstant();
          long debit = rs.getLong(4);
          long credit = rs.getLong(5);
          if (credit > 0) {
            Live funded = byFundingKey.get(key);
            if (funded != null) {
              funded.granted = Math.addExact(funded.granted, credit);
              funded.remaining = Math.addExact(funded.remaining, credit);
            } else if (EntryType.TOP_UP.code().equals(type)) {
              cash.granted = Math.addExact(cash.granted, credit);
              cash.remaining = Math.addExact(cash.remaining, credit);
            } else {
              undo(draws, credit, at, cash);
            }
          } else if (EntryType.GRANT_EXPIRY.code().equals(type)) {
            Live expired =
                byGrant.get(key.substring("grant:".length(), key.length() - ":expiry".length()));
            expired.remaining = Math.subtractExact(expired.remaining, debit);
          } else {
            draw(drawOrder, draws, debit, at, cash);
          }
        }
      }
    }
    List<Lot> out = new ArrayList<>();
    for (Live l : drawOrder) {
      out.add(new Lot(l.id, l.expiresAt, l.granted, l.remaining));
    }
    return out;
  }

  /** Draws {@code cents} expiring-first from lots live at {@code at}; the rest from cash. */
  private static void draw(List<Live> order, Deque<Draw> draws, long cents, Instant at, Live cash) {
    long left = cents;
    for (Live l : order) {
      if (left == 0) {
        return;
      }
      if (l == cash || !l.liveAt(at) || l.remaining <= 0) {
        continue;
      }
      long take = Math.min(left, l.remaining);
      l.remaining -= take;
      left -= take;
      draws.push(new Draw(l, take));
    }
    if (left > 0) {
      cash.remaining = Math.subtractExact(cash.remaining, left); // may go negative (overdraft)
      draws.push(new Draw(cash, left));
    }
  }

  /** Gives {@code cents} back by undoing the most recent draws; expired lots refund to cash. */
  private static void undo(Deque<Draw> draws, long cents, Instant at, Live cash) {
    long left = cents;
    while (left > 0 && !draws.isEmpty()) {
      Draw d = draws.pop();
      long back = Math.min(left, d.cents());
      Live target = d.lot().liveAt(at) ? d.lot() : cash;
      target.remaining = Math.addExact(target.remaining, back);
      left -= back;
      if (back < d.cents()) {
        draws.push(new Draw(d.lot(), d.cents() - back));
      }
    }
    if (left > 0) {
      cash.remaining = Math.addExact(cash.remaining, left);
    }
  }
}
