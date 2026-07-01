package com.logicvoid0x.billing.catalog.store;

import com.logicvoid0x.billing.catalog.launch.ModelOffering;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.money.Cents.Rate;
import com.logicvoid0x.billing.rating.ContextBandRule;
import com.logicvoid0x.billing.rating.ContractPrice;
import com.logicvoid0x.billing.rating.EffectivePeriod;
import com.logicvoid0x.billing.rating.ListPrice;
import com.logicvoid0x.billing.rating.Pricing;
import com.logicvoid0x.billing.rating.RatingCatalog;
import com.logicvoid0x.billing.rating.Sku;
import com.logicvoid0x.billing.rating.Weight;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC access to the {@code catalog} schema.
 *
 * <p>Reads return a {@link RatingCatalog} snapshot of the rows in effect during one hour, read in
 * one repeatable-read transaction together with the catalog version they belong to.
 *
 * <p>Writes are idempotent on each row's business key. Re-sending an identical row is a no-op. A
 * row whose key exists with different content, or whose period overlaps another row's, is rejected
 * with {@link CatalogEditRejectedException}: catalog rows are never edited (ADR 0004).
 */
public final class CatalogStore {
  private static final String SQLSTATE_UNIQUE = "23505";
  private static final String SQLSTATE_EXCLUSION = "23P01";
  private static final String SQLSTATE_FOREIGN_KEY = "23503";

  private final DataSource dataSource;

  /** Creates the store over a data source whose database has the catalog migrations applied. */
  public CatalogStore(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  // ------------------------------------------------------------------ reads

  /** The current catalog version; it moves forward on every write. */
  public long version() {
    try (Connection c = dataSource.getConnection()) {
      return version(c);
    } catch (SQLException e) {
      throw new CatalogException("reading the catalog version failed", e);
    }
  }

  /**
   * The rows in effect at any time during the hour that contains {@code at}.
   *
   * @return a snapshot whose version is {@code "catalog-v" + version}
   */
  public RatingCatalog snapshot(Instant at) {
    Instant from = at.truncatedTo(ChronoUnit.HOURS);
    Instant until = from.plus(1, ChronoUnit.HOURS);
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      try {
        long version = version(c);
        RatingCatalog catalog =
            new RatingCatalog(
                versionName(version),
                skus(c, from, until),
                bandRules(c, from, until),
                listPrices(c, from, until),
                contractPrices(c, from, until),
                weights(c, from, until));
        c.commit();
        return catalog;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new CatalogException("reading the catalog snapshot failed", e);
    }
  }

  /** The version string a snapshot at {@code version} carries. */
  public static String versionName(long version) {
    return "catalog-v" + version;
  }

  /** The version number in a snapshot version string made by {@link #versionName}. */
  public static long versionNumber(String versionName) {
    if (!versionName.startsWith("catalog-v")) {
      throw new IllegalArgumentException("not a catalog version: " + versionName);
    }
    return Long.parseLong(versionName.substring("catalog-v".length()));
  }

  /** The SKU with {@code skuId}, if it exists. */
  public Optional<Sku> sku(String skuId) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement s = c.prepareStatement(SKU_SELECT + " WHERE sku_id = ?")) {
      s.setString(1, skuId);
      try (ResultSet rs = s.executeQuery()) {
        return rs.next() ? Optional.of(sku(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new CatalogException("reading SKU " + skuId + " failed", e);
    }
  }

  private static final String SKU_SELECT =
      "SELECT sku_id, sku_key, meter_code, model, service_tier, context_band, effective_from,"
          + " effective_until FROM catalog.sku";
  private static final String OVERLAPS =
      " WHERE tstzrange(effective_from, effective_until, '[)') && tstzrange(?, ?, '[)')";

  private static long version(Connection c) throws SQLException {
    try (PreparedStatement s =
            c.prepareStatement("SELECT version FROM catalog.catalog_version WHERE id");
        ResultSet rs = s.executeQuery()) {
      if (!rs.next()) {
        throw new CatalogException("catalog.catalog_version has no row");
      }
      return rs.getLong(1);
    }
  }

  private static List<Sku> skus(Connection c, Instant from, Instant until) throws SQLException {
    List<Sku> out = new ArrayList<>();
    try (PreparedStatement s =
            overlapping(c, SKU_SELECT + OVERLAPS + " ORDER BY sku_id", from, until);
        ResultSet rs = s.executeQuery()) {
      while (rs.next()) {
        out.add(sku(rs));
      }
    }
    return out;
  }

  private static Sku sku(ResultSet rs) throws SQLException {
    return new Sku(
        rs.getString(1),
        rs.getString(2),
        new MeterCode(rs.getString(3)),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        period(rs, 7));
  }

  private static List<ContextBandRule> bandRules(Connection c, Instant from, Instant until)
      throws SQLException {
    List<ContextBandRule> out = new ArrayList<>();
    try (PreparedStatement s =
            overlapping(
                c,
                "SELECT model, threshold_tokens, base_band, above_band, effective_from,"
                    + " effective_until FROM catalog.context_band_rule"
                    + OVERLAPS
                    + " ORDER BY model, effective_from",
                from,
                until);
        ResultSet rs = s.executeQuery()) {
      while (rs.next()) {
        out.add(
            new ContextBandRule(
                rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), period(rs, 5)));
      }
    }
    return out;
  }

  private static List<ListPrice> listPrices(Connection c, Instant from, Instant until)
      throws SQLException {
    Map<String, Priced> rows = new LinkedHashMap<>();
    try (PreparedStatement s =
            overlapping(
                c,
                "SELECT p.sku_id, p.effective_from, p.effective_until, t.up_to, t.rate_cents,"
                    + " t.per_units FROM catalog.list_price p JOIN catalog.list_price_tier t"
                    + " USING (sku_id, effective_from)"
                    + OVERLAPS.replace(
                        "effective_from, effective_until", "p.effective_from, p.effective_until")
                    + " ORDER BY p.sku_id, p.effective_from, t.tier_no",
                from,
                until);
        ResultSet rs = s.executeQuery()) {
      while (rs.next()) {
        String key = rs.getString(1) + "|" + instant(rs, 2);
        Priced p = rows.get(key);
        if (p == null) {
          p = new Priced(null, rs.getString(1), period(rs, 2));
          rows.put(key, p);
        }
        p.tiers.add(tier(rs, 4));
      }
    }
    List<ListPrice> out = new ArrayList<>();
    for (Priced p : rows.values()) {
      out.add(new ListPrice(p.skuId, pricing(p.tiers), p.period));
    }
    return out;
  }

  private static List<ContractPrice> contractPrices(Connection c, Instant from, Instant until)
      throws SQLException {
    Map<String, Priced> rows = new LinkedHashMap<>();
    try (PreparedStatement s =
            overlapping(
                c,
                "SELECT p.sku_id, p.effective_from, p.effective_until, t.up_to, t.rate_cents,"
                    + " t.per_units, p.org_id FROM catalog.contract_price p"
                    + " JOIN catalog.contract_price_tier t USING (org_id, sku_id, effective_from)"
                    + OVERLAPS.replace(
                        "effective_from, effective_until", "p.effective_from, p.effective_until")
                    + " ORDER BY p.org_id, p.sku_id, p.effective_from, t.tier_no",
                from,
                until);
        ResultSet rs = s.executeQuery()) {
      while (rs.next()) {
        String key = rs.getString(7) + "|" + rs.getString(1) + "|" + instant(rs, 2);
        Priced p = rows.get(key);
        if (p == null) {
          p = new Priced(rs.getString(7), rs.getString(1), period(rs, 2));
          rows.put(key, p);
        }
        p.tiers.add(tier(rs, 4));
      }
    }
    List<ContractPrice> out = new ArrayList<>();
    for (Priced p : rows.values()) {
      out.add(new ContractPrice(p.orgId, p.skuId, pricing(p.tiers), p.period));
    }
    return out;
  }

  private static List<Weight> weights(Connection c, Instant from, Instant until)
      throws SQLException {
    List<Weight> out = new ArrayList<>();
    try (PreparedStatement s =
            overlapping(
                c,
                "SELECT sku_id, units, per_tokens, effective_from, effective_until"
                    + " FROM catalog.allowance_weight"
                    + OVERLAPS
                    + " ORDER BY sku_id, effective_from",
                from,
                until);
        ResultSet rs = s.executeQuery()) {
      while (rs.next()) {
        out.add(new Weight(rs.getString(1), new Rate(rs.getLong(2), rs.getLong(3)), period(rs, 4)));
      }
    }
    return out;
  }

  /** A price row being assembled from its tier rows. */
  private static final class Priced {
    final String orgId;
    final String skuId;
    final EffectivePeriod period;
    final List<Pricing.Tier> tiers = new ArrayList<>();

    Priced(String orgId, String skuId, EffectivePeriod period) {
      this.orgId = orgId;
      this.skuId = skuId;
      this.period = period;
    }
  }

  private static Pricing.Tier tier(ResultSet rs, int upToColumn) throws SQLException {
    long upTo = rs.getLong(upToColumn);
    if (rs.wasNull()) {
      upTo = Long.MAX_VALUE;
    }
    return new Pricing.Tier(upTo, new Rate(rs.getLong(upToColumn + 1), rs.getLong(upToColumn + 2)));
  }

  /** One open tier is flat pricing; anything else is graduated (validated by {@link Pricing}). */
  static Pricing pricing(List<Pricing.Tier> tiers) {
    if (tiers.size() == 1 && tiers.get(0).upTo() == Long.MAX_VALUE) {
      return Pricing.flat(tiers.get(0).rate());
    }
    return Pricing.graduated(tiers);
  }

  private static List<Pricing.Tier> tiers(Pricing pricing) {
    return switch (pricing) {
      case Pricing.Flat f -> List.of(new Pricing.Tier(Long.MAX_VALUE, f.rate()));
      case Pricing.Graduated g -> g.tiers();
    };
  }

  private static EffectivePeriod period(ResultSet rs, int fromColumn) throws SQLException {
    Instant from = instant(rs, fromColumn);
    Instant until = instantOrNull(rs, fromColumn + 1);
    return until == null ? EffectivePeriod.from(from) : EffectivePeriod.between(from, until);
  }

  private static OffsetDateTime utc(Instant t) {
    return t.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    return Objects.requireNonNull(instantOrNull(rs, column), "timestamp column " + column);
  }

  private static Instant instantOrNull(ResultSet rs, int column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static PreparedStatement overlapping(
      Connection c, String sql, Instant from, Instant until) throws SQLException {
    PreparedStatement s = c.prepareStatement(sql);
    try {
      s.setObject(1, utc(from));
      s.setObject(2, utc(until));
      return s;
    } catch (SQLException e) {
      s.close();
      throw e;
    }
  }

  // ------------------------------------------------------------------ writes

  /**
   * Adds a SKU. Idempotent on {@code sku_id}; a SKU that exists with any different field is
   * rejected, because SKUs are never edited.
   *
   * @return true if the SKU was added now, false if the identical SKU already existed
   */
  public boolean addSku(Sku sku) {
    return write(
        "SKU " + sku.skuId(),
        c -> {
          int added;
          try (PreparedStatement s =
              c.prepareStatement(
                  "INSERT INTO catalog.sku (sku_id, sku_key, meter_code, model, service_tier,"
                      + " context_band, effective_from, effective_until)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (sku_id) DO NOTHING")) {
            s.setString(1, sku.skuId());
            s.setString(2, sku.key());
            s.setString(3, sku.meter().code());
            s.setString(4, sku.model());
            s.setString(5, sku.serviceTier());
            s.setString(6, sku.contextBand());
            setPeriod(s, 7, sku.effective());
            added = s.executeUpdate();
          }
          if (added == 1) {
            return true;
          }
          Sku existing;
          try (PreparedStatement s = c.prepareStatement(SKU_SELECT + " WHERE sku_id = ?")) {
            s.setString(1, sku.skuId());
            try (ResultSet rs = s.executeQuery()) {
              rs.next();
              existing = sku(rs);
            }
          }
          return requireSame("SKU " + sku.skuId(), existing, sku);
        });
  }

  /** Adds a context-band rule. Idempotent on (model, effective_from). */
  public boolean addBandRule(ContextBandRule rule) {
    String what = "context-band rule " + rule.model() + "@" + rule.effective().from();
    return write(
        what,
        c -> {
          try (PreparedStatement s =
              c.prepareStatement(
                  "INSERT INTO catalog.context_band_rule (model, effective_from, effective_until,"
                      + " threshold_tokens, base_band, above_band) VALUES (?, ?, ?, ?, ?, ?)"
                      + " ON CONFLICT (model, effective_from) DO NOTHING")) {
            s.setString(1, rule.model());
            setPeriod(s, 2, rule.effective());
            s.setLong(4, rule.thresholdTokens());
            s.setString(5, rule.baseBand());
            s.setString(6, rule.aboveBand());
            if (s.executeUpdate() == 1) {
              return true;
            }
          }
          try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT model, threshold_tokens, base_band, above_band, effective_from,"
                      + " effective_until FROM catalog.context_band_rule"
                      + " WHERE model = ? AND effective_from = ?")) {
            s.setString(1, rule.model());
            s.setObject(2, utc(rule.effective().from()));
            try (ResultSet rs = s.executeQuery()) {
              rs.next();
              ContextBandRule existing =
                  new ContextBandRule(
                      rs.getString(1),
                      rs.getLong(2),
                      rs.getString(3),
                      rs.getString(4),
                      period(rs, 5));
              return requireSame(what, existing, rule);
            }
          }
        });
  }

  /** Adds a list price under {@code priceBookVersion}. Idempotent on (sku_id, effective_from). */
  public boolean addListPrice(ListPrice price, int priceBookVersion) {
    String what = "list price " + price.skuId() + "@" + price.effective().from();
    return write(
        what,
        c -> {
          try (PreparedStatement s =
              c.prepareStatement(
                  "INSERT INTO catalog.list_price (sku_id, effective_from, effective_until,"
                      + " price_book_version) VALUES (?, ?, ?, ?)"
                      + " ON CONFLICT (sku_id, effective_from) DO NOTHING")) {
            s.setString(1, price.skuId());
            setPeriod(s, 2, price.effective());
            s.setInt(4, priceBookVersion);
            if (s.executeUpdate() == 1) {
              insertTiers(
                  c,
                  "INSERT INTO catalog.list_price_tier (sku_id, effective_from, tier_no, up_to,"
                      + " rate_cents, per_units) VALUES (?, ?, ?, ?, ?, ?)",
                  List.of(price.skuId()),
                  price.effective().from(),
                  price.pricing());
              return true;
            }
          }
          ExistingPrice existing =
              existingPrice(
                  c,
                  "SELECT p.effective_until, p.price_book_version, t.up_to, t.rate_cents,"
                      + " t.per_units FROM catalog.list_price p JOIN catalog.list_price_tier t"
                      + " USING (sku_id, effective_from) WHERE p.sku_id = ? AND p.effective_from = ?"
                      + " ORDER BY t.tier_no",
                  List.of(price.skuId()),
                  price.effective().from());
          requireSame(
              what,
              existing,
              new ExistingPrice(price.effective().until(), priceBookVersion, price.pricing()));
          return false;
        });
  }

  /**
   * Adds a contract override under {@code contractVersion}. Idempotent on (org_id, sku_id,
   * effective_from).
   */
  public boolean addContractPrice(ContractPrice price, int contractVersion) {
    String what =
        "contract price " + price.orgId() + "/" + price.skuId() + "@" + price.effective().from();
    return write(
        what,
        c -> {
          try (PreparedStatement s =
              c.prepareStatement(
                  "INSERT INTO catalog.contract_price (org_id, sku_id, effective_from,"
                      + " effective_until, contract_version) VALUES (?, ?, ?, ?, ?)"
                      + " ON CONFLICT (org_id, sku_id, effective_from) DO NOTHING")) {
            s.setString(1, price.orgId());
            s.setString(2, price.skuId());
            setPeriod(s, 3, price.effective());
            s.setInt(5, contractVersion);
            if (s.executeUpdate() == 1) {
              insertTiers(
                  c,
                  "INSERT INTO catalog.contract_price_tier (org_id, sku_id, effective_from,"
                      + " tier_no, up_to, rate_cents, per_units) VALUES (?, ?, ?, ?, ?, ?, ?)",
                  List.of(price.orgId(), price.skuId()),
                  price.effective().from(),
                  price.pricing());
              return true;
            }
          }
          ExistingPrice existing =
              existingPrice(
                  c,
                  "SELECT p.effective_until, p.contract_version, t.up_to, t.rate_cents,"
                      + " t.per_units FROM catalog.contract_price p"
                      + " JOIN catalog.contract_price_tier t USING (org_id, sku_id, effective_from)"
                      + " WHERE p.org_id = ? AND p.sku_id = ? AND p.effective_from = ?"
                      + " ORDER BY t.tier_no",
                  List.of(price.orgId(), price.skuId()),
                  price.effective().from());
          requireSame(
              what,
              existing,
              new ExistingPrice(price.effective().until(), contractVersion, price.pricing()));
          return false;
        });
  }

  /** Adds an allowance weight. Idempotent on (sku_id, effective_from). */
  public boolean addWeight(Weight weight) {
    String what = "weight " + weight.skuId() + "@" + weight.effective().from();
    return write(
        what,
        c -> {
          try (PreparedStatement s =
              c.prepareStatement(
                  "INSERT INTO catalog.allowance_weight (sku_id, effective_from, effective_until,"
                      + " units, per_tokens) VALUES (?, ?, ?, ?, ?)"
                      + " ON CONFLICT (sku_id, effective_from) DO NOTHING")) {
            s.setString(1, weight.skuId());
            setPeriod(s, 2, weight.effective());
            s.setLong(4, weight.unitsPer().cents());
            s.setLong(5, weight.unitsPer().perUnits());
            if (s.executeUpdate() == 1) {
              return true;
            }
          }
          try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT sku_id, units, per_tokens, effective_from, effective_until"
                      + " FROM catalog.allowance_weight WHERE sku_id = ? AND effective_from = ?")) {
            s.setString(1, weight.skuId());
            s.setObject(2, utc(weight.effective().from()));
            try (ResultSet rs = s.executeQuery()) {
              rs.next();
              Weight existing =
                  new Weight(
                      rs.getString(1), new Rate(rs.getLong(2), rs.getLong(3)), period(rs, 4));
              return requireSame(what, existing, weight);
            }
          }
        });
  }

  /** Registers that a model emits a meter in a service tier. Idempotent; rows are never removed. */
  public boolean addOffering(ModelOffering offering) {
    return write(
        "model offering " + offering,
        c -> {
          try (PreparedStatement s =
              c.prepareStatement(
                  "INSERT INTO catalog.model_offering (model, service_tier, meter_code)"
                      + " VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setString(1, offering.model());
            s.setString(2, offering.serviceTier());
            s.setString(3, offering.meter().code());
            return s.executeUpdate() == 1;
          }
        });
  }

  /** The model registry, ordered by model, tier and meter. */
  public List<ModelOffering> offerings() {
    List<ModelOffering> out = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT model, service_tier, meter_code FROM catalog.model_offering"
                    + " ORDER BY model, service_tier, meter_code");
        ResultSet rs = s.executeQuery()) {
      while (rs.next()) {
        out.add(
            new ModelOffering(rs.getString(1), rs.getString(2), new MeterCode(rs.getString(3))));
      }
    } catch (SQLException e) {
      throw new CatalogException("reading the model registry failed", e);
    }
    return out;
  }

  /** Writes every row of {@code catalog} (idempotent); list and contract rows get version 1. */
  public void addAll(RatingCatalog catalog) {
    catalog.skus().forEach(this::addSku);
    catalog.bandRules().forEach(this::addBandRule);
    catalog.listPrices().forEach(p -> addListPrice(p, 1));
    catalog.contractPrices().forEach(p -> addContractPrice(p, 1));
    catalog.weights().forEach(this::addWeight);
  }

  /** Content of an existing price row, compared with a re-sent one. */
  private record ExistingPrice(Instant until, int version, Pricing pricing) {}

  private static ExistingPrice existingPrice(
      Connection c, String sql, List<String> keys, Instant from) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      int i = 1;
      for (String k : keys) {
        s.setString(i++, k);
      }
      s.setObject(i, utc(from));
      try (ResultSet rs = s.executeQuery()) {
        Instant until = null;
        int version = 0;
        List<Pricing.Tier> tiers = new ArrayList<>();
        while (rs.next()) {
          until = instantOrNull(rs, 1);
          version = rs.getInt(2);
          tiers.add(tier(rs, 3));
        }
        if (tiers.isEmpty()) {
          throw new CatalogException("price row without tiers: " + keys + "@" + from);
        }
        return new ExistingPrice(until, version, pricing(tiers));
      }
    }
  }

  private static void insertTiers(
      Connection c, String sql, List<String> keys, Instant from, Pricing pricing)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      int tierNo = 1;
      for (Pricing.Tier t : tiers(pricing)) {
        int i = 1;
        for (String k : keys) {
          s.setString(i++, k);
        }
        s.setObject(i++, utc(from));
        s.setInt(i++, tierNo++);
        if (t.upTo() == Long.MAX_VALUE) {
          s.setNull(i++, Types.BIGINT);
        } else {
          s.setLong(i++, t.upTo());
        }
        s.setLong(i++, t.rate().cents());
        s.setLong(i, t.rate().perUnits());
        s.addBatch();
      }
      s.executeBatch();
    }
  }

  private static <T> boolean requireSame(String what, T existing, T sent) {
    if (!existing.equals(sent)) {
      throw new CatalogEditRejectedException(
          what
              + " already exists with different content; catalog rows are never edited, add a new"
              + " effective-dated row instead (existing: "
              + existing
              + ")");
    }
    return false;
  }

  private static void setPeriod(PreparedStatement s, int index, EffectivePeriod period)
      throws SQLException {
    s.setObject(index, utc(period.from()));
    if (period.until() == null) {
      s.setNull(index + 1, Types.TIMESTAMP_WITH_TIMEZONE);
    } else {
      s.setObject(index + 1, utc(period.until()));
    }
  }

  @FunctionalInterface
  private interface Write {
    boolean run(Connection c) throws SQLException;
  }

  /** Runs one write in its own transaction, mapping key and overlap conflicts to rejections. */
  private boolean write(String what, Write write) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        boolean added = write.run(c);
        c.commit();
        return added;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      String state = e.getSQLState();
      if (SQLSTATE_EXCLUSION.equals(state)) {
        throw new CatalogEditRejectedException(
            what + " overlaps the period of an existing row with the same key", e);
      }
      if (SQLSTATE_UNIQUE.equals(state)) {
        throw new CatalogEditRejectedException(what + " reuses a unique key: " + e.getMessage(), e);
      }
      if (SQLSTATE_FOREIGN_KEY.equals(state)) {
        throw new CatalogEditRejectedException(what + " refers to an unknown SKU", e);
      }
      throw new CatalogException("writing " + what + " failed", e);
    }
  }
}
