package com.logicvoid0x.billing.usageview;

import com.logicvoid0x.billing.rating.RatingCatalog;
import com.logicvoid0x.billing.rating.Sku;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * Usage by org, workspace, API key, model and hour, read from {@code metering.usage_hourly_by_key}
 * (design Q12, option b). That rollup is written in the same transaction as {@code usage_hourly},
 * so these rows summed over workspace and key equal it exactly. The model comes from the SKU in the
 * catalog of the row's hour.
 */
public final class UsageByKey {
  /** Model shown for a SKU the catalog of that hour does not know. */
  public static final String UNKNOWN_MODEL = "unknown";

  /**
   * One row.
   *
   * @param hour start of the event hour
   * @param workspaceId the workspace
   * @param apiKeyId the API key
   * @param model the model of the SKU
   * @param skuId the SKU
   * @param quantity units of the SKU's meter
   * @param lines metered lines
   */
  public record Row(
      Instant hour,
      String workspaceId,
      String apiKeyId,
      String model,
      String skuId,
      long quantity,
      long lines) {}

  private final DataSource dataSource;
  private final Function<Instant, RatingCatalog> catalogs;

  /**
   * Creates the reader.
   *
   * @param dataSource the billing database (metering schema)
   * @param catalogs the catalog in effect at an hour
   */
  public UsageByKey(DataSource dataSource, Function<Instant, RatingCatalog> catalogs) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
  }

  /** Rows of {@code orgId} for hours in {@code [from, until)}, by hour, workspace, key and SKU. */
  public List<Row> rows(String orgId, Instant from, Instant until) throws SQLException {
    List<Row> out = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT event_hour, workspace_id, api_key_id, sku_id, quantity, lines"
                    + " FROM metering.usage_hourly_by_key"
                    + " WHERE org_id = ? AND event_hour >= ? AND event_hour < ?"
                    + " ORDER BY event_hour, workspace_id, api_key_id, sku_id")) {
      s.setString(1, orgId);
      s.setTimestamp(2, Timestamp.from(from));
      s.setTimestamp(3, Timestamp.from(until));
      try (ResultSet rs = s.executeQuery()) {
        Instant catalogHour = null;
        RatingCatalog catalog = null;
        while (rs.next()) {
          Instant hour = rs.getTimestamp(1).toInstant();
          if (!hour.equals(catalogHour)) {
            catalog = catalogs.apply(hour);
            catalogHour = hour;
          }
          String sku = rs.getString(4);
          out.add(
              new Row(
                  hour,
                  rs.getString(2),
                  rs.getString(3),
                  model(catalog, sku),
                  sku,
                  rs.getLong(5),
                  rs.getLong(6)));
        }
      }
    }
    return out;
  }

  private static String model(RatingCatalog catalog, String skuId) {
    for (Sku s : catalog.skus()) {
      if (s.skuId().equals(skuId)) {
        return s.model();
      }
    }
    return UNKNOWN_MODEL;
  }
}
