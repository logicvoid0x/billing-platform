package com.logicvoid0x.billing.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.catalog.db.CatalogMigrations;
import com.logicvoid0x.billing.catalog.store.CatalogEditRejectedException;
import com.logicvoid0x.billing.catalog.store.CatalogStore;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.money.Cents.Rate;
import com.logicvoid0x.billing.rating.ContextBandRule;
import com.logicvoid0x.billing.rating.ContractPrice;
import com.logicvoid0x.billing.rating.EffectivePeriod;
import com.logicvoid0x.billing.rating.ListPrice;
import com.logicvoid0x.billing.rating.Pricing;
import com.logicvoid0x.billing.rating.RatingCatalog;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.Sku;
import com.logicvoid0x.billing.rating.Weight;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import com.logicvoid0x.billing.rating.golden.GoldenCases.GoldenCase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The catalog read API against real Postgres: the rating golden cases priced from catalog data
 * loaded from the database, idempotent writes, rejected edits, and the effective-date cache.
 */
@Testcontainers
class CatalogStoreTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final Instant V1 = GoldenCases.V1;
  private static final Instant V2 = Instant.parse("2026-11-15T00:00:00Z");
  private static final Rate PER_M_15 = new Rate(1500, 1_000_000);

  private static CatalogStore store;

  @BeforeAll
  static void setUp() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    CatalogMigrations.migrate(ds);
    store = new CatalogStore(ds);
    store.addAll(GoldenCases.catalog());
  }

  private static CatalogCache cache(Clock clock) {
    return new CatalogCache(store, clock, Duration.ofSeconds(30), "test", 64);
  }

  static Stream<GoldenCase> goldenCases() {
    return GoldenCases.cases().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("goldenCases")
  void goldenCasesPassAgainstCatalogDataLoadedFromTheDatabase(GoldenCase c) {
    RatingLibrary rating = cache(Clock.systemUTC()).at(GoldenCases.HOUR);
    assertThat(c.chargedCents(rating)).isEqualTo(c.expectedCents());
  }

  @Test
  void theLoadedSnapshotEqualsTheGoldenCatalogRowForRow() {
    RatingCatalog golden = GoldenCases.catalog();
    RatingCatalog loaded = store.snapshot(GoldenCases.HOUR);
    assertThat(loaded.skus()).containsAll(golden.skus());
    assertThat(loaded.listPrices()).containsAll(golden.listPrices());
    assertThat(loaded.contractPrices()).containsAll(golden.contractPrices());
    assertThat(loaded.version()).startsWith("catalog-v");
  }

  @Test
  void reSendingTheSameRowsIsANoOp() {
    long before = store.version();
    RatingCatalog golden = GoldenCases.catalog();
    assertThat(store.addSku(golden.skus().get(0))).isFalse();
    assertThat(store.addListPrice(golden.listPrices().get(1), 1)).as("graduated").isFalse();
    assertThat(store.addContractPrice(golden.contractPrices().get(0), 1)).isFalse();
    assertThat(store.version()).as("no-op writes do not change the catalog").isEqualTo(before);
  }

  @Test
  void anEditToAnExistingSkuIsRejected() {
    Sku original = GoldenCases.catalog().skus().get(0);
    Sku edited =
        new Sku(
            original.skuId(),
            original.key(),
            original.meter(),
            "another-model",
            original.serviceTier(),
            original.contextBand(),
            original.effective());

    assertThatThrownBy(() -> store.addSku(edited))
        .isInstanceOf(CatalogEditRejectedException.class)
        .hasMessageContaining("never edited");
    assertThat(store.sku(original.skuId())).contains(original);
  }

  @Test
  void aChangedPriceUnderAnExistingKeyIsRejected() {
    ListPrice original = GoldenCases.catalog().listPrices().get(0);
    ListPrice changed =
        new ListPrice(original.skuId(), Pricing.flat(new Rate(1, 1_000_000)), original.effective());
    assertThatThrownBy(() -> store.addListPrice(changed, 1))
        .isInstanceOf(CatalogEditRejectedException.class);
    assertThatThrownBy(() -> store.addListPrice(original, 2))
        .as("same price, different price book version")
        .isInstanceOf(CatalogEditRejectedException.class);
  }

  @Test
  void overlappingRowsAndUnknownSkusAreRejected() {
    ListPrice overlapping =
        new ListPrice("sku_out", Pricing.flat(PER_M_15), EffectivePeriod.from(V2));
    assertThatThrownBy(() -> store.addListPrice(overlapping, 2))
        .isInstanceOf(CatalogEditRejectedException.class)
        .hasMessageContaining("overlaps");
    assertThatThrownBy(() ->
            store.addWeight(new Weight("sku_missing", new Rate(1, 1), EffectivePeriod.from(V1))))
        .isInstanceOf(CatalogEditRejectedException.class)
        .hasMessageContaining("unknown SKU");
  }

  @Test
  void aSnapshotHoldsTheRowsInEffectDuringItsHour() {
    Sku sku = sku("p3_change", MeterCode.INPUT_TOKENS);
    store.addSku(sku);
    store.addListPrice(
        new ListPrice(sku.skuId(), Pricing.flat(PER_M_15), EffectivePeriod.between(V1, V2)), 1);
    store.addListPrice(
        new ListPrice(
            sku.skuId(), Pricing.flat(new Rate(1800, 1_000_000)), EffectivePeriod.from(V2)),
        2);
    store.addWeight(new Weight(sku.skuId(), new Rate(1, 10), EffectivePeriod.from(V1)));
    store.addBandRule(
        new ContextBandRule("p3_change-model", 200_000, "base", "long", EffectivePeriod.from(V1)));

    Instant lastOldHour = V2.minusSeconds(3600);
    CatalogCache cache = cache(Clock.systemUTC());
    assertThat(cache.at(lastOldHour.plusSeconds(1800)).rate("org_any", sku.skuId(), lastOldHour))
        .isEqualTo(PER_M_15);
    assertThat(cache.at(V2).rate("org_any", sku.skuId(), V2)).isEqualTo(new Rate(1800, 1_000_000));
    assertThat(store.snapshot(lastOldHour).listPrices())
        .filteredOn(p -> p.skuId().equals(sku.skuId()))
        .hasSize(1);
    assertThat(cache.at(V2).usageUnits(sku.skuId(), 1_000, V2)).isEqualTo(100);
    assertThat(store.snapshot(V2).bandRules())
        .extracting(ContextBandRule::model)
        .contains("p3_change-model");
  }

  @Test
  void contractOverridesWithTiersRoundTrip() {
    Sku sku = sku("p3_tiered", MeterCode.OUTPUT_TOKENS);
    store.addSku(sku);
    Pricing tiers =
        Pricing.graduated(
            List.of(
                new Pricing.Tier(1_000, new Rate(10, 1)),
                new Pricing.Tier(Long.MAX_VALUE, new Rate(5, 1))));
    ContractPrice contract =
        new ContractPrice("org_tier", sku.skuId(), tiers, EffectivePeriod.from(V1));
    assertThat(store.addContractPrice(contract, 3)).isTrue();
    assertThat(store.snapshot(GoldenCases.HOUR).contractPrices()).contains(contract);
  }

  @Test
  void theCacheReusesAnHourUntilTheCatalogVersionMoves() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-01T12:00:00Z"));
    CatalogCache cache = cache(clock);

    RatingLibrary first = cache.at(GoldenCases.HOUR);
    assertThat(cache.at(GoldenCases.HOUR.plusSeconds(3599))).isSameAs(first);
    assertThat(cache.loads()).isEqualTo(1);

    store.addSku(sku("p3_cache", MeterCode.INPUT_TOKENS));
    assertThat(cache.at(GoldenCases.HOUR)).as("within the check interval").isSameAs(first);

    clock.now = clock.now.plusSeconds(31);
    RatingLibrary reloaded = cache.at(GoldenCases.HOUR);
    assertThat(reloaded).isNotSameAs(first);
    assertThat(reloaded.version()).isNotEqualTo(first.version());
    assertThat(cache.loads()).isEqualTo(2);
  }

  private static Sku sku(String id, MeterCode meter) {
    return new Sku(
        id, id + "/key", meter, id + "-model", "standard", "base", EffectivePeriod.from(V1));
  }

  private static final class MutableClock extends Clock {
    Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
