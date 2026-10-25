package com.logicvoid0x.billing.contracts.catalog;

import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.v1.CatalogBandRule;
import com.logicvoid0x.billing.contracts.v1.CatalogContractPrice;
import com.logicvoid0x.billing.contracts.v1.CatalogListPrice;
import com.logicvoid0x.billing.contracts.v1.CatalogPeriod;
import com.logicvoid0x.billing.contracts.v1.CatalogPricing;
import com.logicvoid0x.billing.contracts.v1.CatalogSku;
import com.logicvoid0x.billing.contracts.v1.CatalogSnapshot;
import com.logicvoid0x.billing.contracts.v1.CatalogTier;
import com.logicvoid0x.billing.contracts.v1.CatalogWeight;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts between {@link RatingCatalog} and the {@code billing.v1.CatalogSnapshot} wire message. A
 * decoded catalog equals the encoded one row for row, so rating from it gives the same cents.
 */
public final class CatalogSnapshotCodec {
  private CatalogSnapshotCodec() {}

  /** The wire form of {@code catalog}, the snapshot of the hour starting at {@code hour}. */
  public static CatalogSnapshot encode(RatingCatalog catalog, Instant hour) {
    CatalogSnapshot.Builder b =
        CatalogSnapshot.newBuilder().setVersion(catalog.version()).setHour(timestamp(hour));
    for (Sku s : catalog.skus()) {
      b.addSkus(
          CatalogSku.newBuilder()
              .setSkuId(s.skuId())
              .setSkuKey(s.key())
              .setMeterCode(s.meter().code())
              .setModel(s.model())
              .setServiceTier(s.serviceTier())
              .setContextBand(s.contextBand())
              .setEffective(period(s.effective())));
    }
    for (ContextBandRule r : catalog.bandRules()) {
      b.addBandRules(
          CatalogBandRule.newBuilder()
              .setModel(r.model())
              .setThresholdTokens(r.thresholdTokens())
              .setBaseBand(r.baseBand())
              .setAboveBand(r.aboveBand())
              .setEffective(period(r.effective())));
    }
    for (ListPrice p : catalog.listPrices()) {
      b.addListPrices(
          CatalogListPrice.newBuilder()
              .setSkuId(p.skuId())
              .setPricing(pricing(p.pricing()))
              .setEffective(period(p.effective())));
    }
    for (ContractPrice p : catalog.contractPrices()) {
      b.addContractPrices(
          CatalogContractPrice.newBuilder()
              .setOrgId(p.orgId())
              .setSkuId(p.skuId())
              .setPricing(pricing(p.pricing()))
              .setEffective(period(p.effective())));
    }
    for (Weight w : catalog.weights()) {
      b.addWeights(
          CatalogWeight.newBuilder()
              .setSkuId(w.skuId())
              .setUnits(w.unitsPer().cents())
              .setPerTokens(w.unitsPer().perUnits())
              .setEffective(period(w.effective())));
    }
    return b.build();
  }

  /**
   * The catalog in {@code wire}.
   *
   * @throws IllegalArgumentException if a required part is missing or a row is invalid
   */
  public static RatingCatalog decode(CatalogSnapshot wire) {
    List<Sku> skus = new ArrayList<>();
    for (CatalogSku s : wire.getSkusList()) {
      skus.add(
          new Sku(
              s.getSkuId(),
              s.getSkuKey(),
              MeterCode.of(s.getMeterCode()),
              s.getModel(),
              s.getServiceTier(),
              s.getContextBand(),
              period(s.hasEffective(), s.getEffective(), "SKU " + s.getSkuId())));
    }
    List<ContextBandRule> rules = new ArrayList<>();
    for (CatalogBandRule r : wire.getBandRulesList()) {
      rules.add(
          new ContextBandRule(
              r.getModel(),
              r.getThresholdTokens(),
              r.getBaseBand(),
              r.getAboveBand(),
              period(r.hasEffective(), r.getEffective(), "band rule " + r.getModel())));
    }
    List<ListPrice> list = new ArrayList<>();
    for (CatalogListPrice p : wire.getListPricesList()) {
      String what = "list price " + p.getSkuId();
      list.add(
          new ListPrice(
              p.getSkuId(),
              pricing(p.hasPricing(), p.getPricing(), what),
              period(p.hasEffective(), p.getEffective(), what)));
    }
    List<ContractPrice> contract = new ArrayList<>();
    for (CatalogContractPrice p : wire.getContractPricesList()) {
      String what = "contract price " + p.getOrgId() + "/" + p.getSkuId();
      contract.add(
          new ContractPrice(
              p.getOrgId(),
              p.getSkuId(),
              pricing(p.hasPricing(), p.getPricing(), what),
              period(p.hasEffective(), p.getEffective(), what)));
    }
    List<Weight> weights = new ArrayList<>();
    for (CatalogWeight w : wire.getWeightsList()) {
      weights.add(
          new Weight(
              w.getSkuId(),
              new Rate(w.getUnits(), w.getPerTokens()),
              period(w.hasEffective(), w.getEffective(), "weight " + w.getSkuId())));
    }
    return new RatingCatalog(wire.getVersion(), skus, rules, list, contract, weights);
  }

  /** Start of the hour {@code wire} covers. */
  public static Instant hour(CatalogSnapshot wire) {
    return instant(wire.getHour());
  }

  private static CatalogPricing pricing(Pricing pricing) {
    CatalogPricing.Builder b = CatalogPricing.newBuilder();
    if (pricing instanceof Pricing.Flat flat) {
      Rate r = flat.rate();
      b.addTiers(CatalogTier.newBuilder().setRateCents(r.cents()).setPerUnits(r.perUnits()));
    } else {
      for (Pricing.Tier t : ((Pricing.Graduated) pricing).tiers()) {
        b.addTiers(
            CatalogTier.newBuilder()
                .setUpTo(t.upTo() == Long.MAX_VALUE ? 0 : t.upTo())
                .setRateCents(t.rate().cents())
                .setPerUnits(t.rate().perUnits()));
      }
    }
    return b.build();
  }

  private static Pricing pricing(boolean present, CatalogPricing wire, String what) {
    if (!present || wire.getTiersCount() == 0) {
      throw new IllegalArgumentException(what + " has no pricing");
    }
    List<Pricing.Tier> tiers = new ArrayList<>();
    for (CatalogTier t : wire.getTiersList()) {
      long upTo = t.getUpTo() == 0 ? Long.MAX_VALUE : t.getUpTo();
      tiers.add(new Pricing.Tier(upTo, new Rate(t.getRateCents(), t.getPerUnits())));
    }
    // Same rule as the catalog store: one open tier is flat pricing.
    if (tiers.size() == 1 && tiers.get(0).upTo() == Long.MAX_VALUE) {
      return Pricing.flat(tiers.get(0).rate());
    }
    return Pricing.graduated(tiers);
  }

  private static CatalogPeriod period(EffectivePeriod p) {
    CatalogPeriod.Builder b = CatalogPeriod.newBuilder().setFrom(timestamp(p.from()));
    if (p.until() != null) {
      b.setUntil(timestamp(p.until()));
    }
    return b.build();
  }

  private static EffectivePeriod period(boolean present, CatalogPeriod wire, String what) {
    if (!present || !wire.hasFrom()) {
      throw new IllegalArgumentException(what + " has no effective period");
    }
    Instant from = instant(wire.getFrom());
    return wire.hasUntil()
        ? EffectivePeriod.between(from, instant(wire.getUntil()))
        : EffectivePeriod.from(from);
  }

  private static Timestamp timestamp(Instant t) {
    return Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
  }

  private static Instant instant(Timestamp t) {
    return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
  }
}
