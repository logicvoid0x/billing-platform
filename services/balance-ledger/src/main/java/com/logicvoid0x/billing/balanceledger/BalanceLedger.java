package com.logicvoid0x.billing.balanceledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The home balance ledger: double-entry, append-only, in long cents (design: "Balance ledger
 * entries"). Every write is idempotent on its business key: re-posting an identical entry is a
 * no-op, and the same key with different content is rejected. The database checks at commit that
 * every entry balances and uses the account kinds its type allows.
 */
public final class BalanceLedger {
  /** A key reused with different content. */
  public static final class ConflictingEntryException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    ConflictingEntryException(String message) {
      super(message);
    }
  }

  /**
   * One movement.
   *
   * @param key business key (e.g. the payment ID for a top-up)
   * @param type the entry type
   * @param orgId the account owner
   * @param region region of the escrow account, for escrow entries; null otherwise
   * @param amountCents amount moved; for TRUE_UP the signed gap (rated minus recognized)
   * @param occurredAt when the movement happened
   */
  public record Entry(
      String key,
      EntryType type,
      String orgId,
      String region,
      long amountCents,
      Instant occurredAt) {
    /** Validates the entry. */
    public Entry {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(orgId, "orgId");
      Objects.requireNonNull(occurredAt, "occurredAt");
      if (amountCents == 0 || (amountCents < 0 && type != EntryType.TRUE_UP)) {
        throw new IllegalArgumentException(
            "amount must be > 0 (only a TRUE_UP carries a signed gap): " + amountCents);
      }
    }
  }

  /** One line of an entry. */
  public record Posting(String accountId, AccountKind kind, long debitCents, long creditCents) {}

  private final DataSource dataSource;

  /** Creates the ledger over a database with the balance_ledger migrations applied. */
  public BalanceLedger(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /** The postings an entry makes: one debit and one credit of the same amount. */
  public static List<Posting> postings(Entry e) {
    AccountKind debit = e.type().debit();
    AccountKind credit = e.type().credit();
    long amount = e.amountCents();
    if (amount < 0) { // TRUE_UP with recognized usage above the rated amount: reverse the sides
      AccountKind t = debit;
      debit = credit;
      credit = t;
      amount = Math.negateExact(amount);
    }
    return List.of(
        new Posting(debit.accountId(e.orgId(), e.region()), debit, amount, 0),
        new Posting(credit.accountId(e.orgId(), e.region()), credit, 0, amount));
  }

  /**
   * Posts an entry.
   *
   * @return true if posted now, false if the identical entry was already posted
   * @throws ConflictingEntryException if the key was used for a different entry
   */
  public boolean post(Entry e) throws SQLException {
    List<Posting> postings = postings(e);
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        boolean inserted;
        try (PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO balance_ledger.entry (entry_key, entry_type, org_id, occurred_at)"
                    + " VALUES (?, ?, ?, ?) ON CONFLICT (entry_key) DO NOTHING")) {
          s.setString(1, e.key());
          s.setString(2, e.type().code());
          s.setString(3, e.orgId());
          s.setTimestamp(4, Timestamp.from(e.occurredAt()));
          inserted = s.executeUpdate() == 1;
        }
        if (!inserted) {
          requireSame(c, e, postings);
          c.rollback();
          return false;
        }
        try (PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO balance_ledger.posting (entry_key, line_no, account_id, account_kind,"
                    + " debit_cents, credit_cents) VALUES (?, ?, ?, ?, ?, ?)")) {
          int line = 1;
          for (Posting p : postings) {
            s.setString(1, e.key());
            s.setInt(2, line++);
            s.setString(3, p.accountId());
            s.setString(4, p.kind().code());
            s.setLong(5, p.debitCents());
            s.setLong(6, p.creditCents());
            s.addBatch();
          }
          s.executeBatch();
        }
        c.commit(); // deferred checks run here
        return true;
      } catch (SQLException | RuntimeException ex) {
        c.rollback();
        throw ex;
      }
    }
  }

  private static void requireSame(Connection c, Entry e, List<Posting> expected)
      throws SQLException {
    String type;
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT entry_type, org_id FROM balance_ledger.entry WHERE entry_key = ?")) {
      s.setString(1, e.key());
      try (ResultSet rs = s.executeQuery()) {
        rs.next();
        type = rs.getString(1);
        if (!type.equals(e.type().code()) || !rs.getString(2).equals(e.orgId())) {
          throw new ConflictingEntryException(
              "entry " + e.key() + " exists as " + type + " for " + rs.getString(2));
        }
      }
    }
    List<Posting> actual = new ArrayList<>();
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT account_id, account_kind, debit_cents, credit_cents FROM balance_ledger.posting"
                + " WHERE entry_key = ? ORDER BY line_no")) {
      s.setString(1, e.key());
      try (ResultSet rs = s.executeQuery()) {
        while (rs.next()) {
          actual.add(
              new Posting(
                  rs.getString(1),
                  AccountKind.ofCode(rs.getString(2)),
                  rs.getLong(3),
                  rs.getLong(4)));
        }
      }
    }
    if (!actual.equals(expected)) {
      throw new ConflictingEntryException(
          "entry " + e.key() + " exists with postings " + actual + ", not " + expected);
    }
  }

  /**
   * An account's balance on its normal (credit) side: credits minus debits. For customer credit
   * this is the spendable credit; it can go negative only within the bounded overdraft.
   */
  public long balance(String accountId) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT coalesce(sum(credit_cents), 0) - coalesce(sum(debit_cents), 0)"
                    + " FROM balance_ledger.posting WHERE account_id = ?")) {
      s.setString(1, accountId);
      try (ResultSet rs = s.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** Spendable customer credit of {@code org}. */
  public long spendable(String org) throws SQLException {
    return balance(AccountKind.CUSTOMER_CREDIT.accountId(org, null));
  }
}
