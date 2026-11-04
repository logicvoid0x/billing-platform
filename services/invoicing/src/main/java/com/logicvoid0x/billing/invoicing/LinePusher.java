package com.logicvoid0x.billing.invoicing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Pushes closed invoice lines to Stripe (requirement: "as an invoice item with its final amount in
 * integer cents (quantity and rate only in the description), never as metered usage or a decimal
 * unit price that Stripe would multiply and round itself. Key: hash(org, sku, period,
 * ledger_version); store pushed state in our own table").
 */
public final class LinePusher {
  /** One closed line. */
  public record Line(
      String orgId,
      String skuId,
      Instant periodStart,
      int ledgerVersion,
      long amountCents,
      String description) {}

  /** The Stripe invoice-item API, as needed here. */
  @FunctionalInterface
  public interface Stripe {
    /**
     * Creates an invoice item of exactly {@code amountCents} (USD) for the org's customer, sending
     * {@code idempotencyKey} as the Idempotency-Key header.
     *
     * @return the Stripe invoice item ID
     */
    String createInvoiceItem(
        String idempotencyKey, String orgId, long amountCents, String description);
  }

  private final DataSource dataSource;
  private final Stripe stripe;

  /** Creates the pusher. */
  public LinePusher(DataSource dataSource, Stripe stripe) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.stripe = Objects.requireNonNull(stripe, "stripe");
  }

  /** hash(org, sku, period, ledger_version), hex SHA-256. */
  public static String key(String orgId, String skuId, Instant periodStart, int ledgerVersion) {
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (orgId + "\u0000" + skuId + "\u0000" + periodStart + "\u0000" + ledgerVersion)
                      .getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Pushes a line once. A re-run of close finds the stored push and sends nothing; a crash between
   * Stripe and the insert re-sends with the same idempotency key, which Stripe answers with the
   * original item.
   *
   * @return the Stripe invoice item ID
   */
  public String push(Line line) throws SQLException {
    String key = key(line.orgId(), line.skuId(), line.periodStart(), line.ledgerVersion());
    Optional<String> done = pushed(key);
    if (done.isPresent()) {
      return done.get();
    }
    String itemId =
        stripe.createInvoiceItem(key, line.orgId(), line.amountCents(), line.description());
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO invoicing.pushed_line (push_key, org_id, sku_id, period_start,"
                    + " ledger_version, amount_cents, stripe_item_id) VALUES (?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (push_key) DO NOTHING")) {
      s.setString(1, key);
      s.setString(2, line.orgId());
      s.setString(3, line.skuId());
      s.setTimestamp(4, Timestamp.from(line.periodStart()));
      s.setInt(5, line.ledgerVersion());
      s.setLong(6, line.amountCents());
      s.setString(7, itemId);
      s.executeUpdate();
    }
    return pushed(key).orElse(itemId);
  }

  /** The Stripe item of the line pushed for (org, SKU, period, version), if any. */
  public Optional<String> pushed(String orgId, String skuId, Instant periodStart, int ledgerVersion)
      throws SQLException {
    return pushed(key(orgId, skuId, periodStart, ledgerVersion));
  }

  /** Sum of every line pushed for (org, SKU, period), across ledger versions. */
  public long pushedTotal(String orgId, String skuId, Instant periodStart) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT coalesce(sum(amount_cents), 0) FROM invoicing.pushed_line"
                    + " WHERE org_id = ? AND sku_id = ? AND period_start = ?")) {
      s.setString(1, orgId);
      s.setString(2, skuId);
      s.setTimestamp(3, Timestamp.from(periodStart));
      try (ResultSet rs = s.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private Optional<String> pushed(String key) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT stripe_item_id FROM invoicing.pushed_line WHERE push_key = ?")) {
      s.setString(1, key);
      try (ResultSet rs = s.executeQuery()) {
        return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
      }
    }
  }
}
