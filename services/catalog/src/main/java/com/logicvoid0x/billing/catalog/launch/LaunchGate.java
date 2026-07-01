package com.logicvoid0x.billing.catalog.launch;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.ContextBandRule;
import com.logicvoid0x.billing.rating.ListPrice;
import com.logicvoid0x.billing.rating.RatingCatalog;
import com.logicvoid0x.billing.rating.Sku;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The launch gate (design: "Model launched without SKUs: launch gate blocks production traffic"). A
 * model may take production traffic only if every registered (model, service tier, meter) resolves,
 * at the checked time, to a SKU in every context band the model can produce, and every such SKU has
 * a list price. Otherwise usage would be unrated, or fail rating.
 */
public final class LaunchGate {
  private LaunchGate() {}

  /** Why one (model, tier, meter, band) cannot be rated. */
  public enum Reason {
    /** The model has no rows in the model registry. */
    NOT_REGISTERED,
    /** No SKU in effect for the combination. */
    NO_SKU,
    /** A SKU exists but has no list price in effect. */
    NO_LIST_PRICE
  }

  /**
   * One gap that fails the gate.
   *
   * @param model the model
   * @param serviceTier the service tier, or empty for {@link Reason#NOT_REGISTERED}
   * @param meter the meter code, or empty for {@link Reason#NOT_REGISTERED}
   * @param contextBand the context band, or empty for {@link Reason#NOT_REGISTERED}
   * @param reason what is missing
   */
  public record Gap(
      String model, String serviceTier, String meter, String contextBand, Reason reason) {}

  /**
   * The gate's verdict.
   *
   * @param at the checked time
   * @param catalogVersion the catalog version the check read
   * @param gaps every gap found, sorted; empty means the gate passes
   */
  public record Result(Instant at, String catalogVersion, List<Gap> gaps) {
    /** Copies the gaps. */
    public Result {
      gaps = List.copyOf(gaps);
    }

    /** True when no gap was found. */
    @JsonProperty("passed")
    public boolean passed() {
      return gaps.isEmpty();
    }
  }

  /**
   * Checks the gate.
   *
   * @param catalog a snapshot of the hour containing {@code at}
   * @param offerings the model registry
   * @param at the time traffic would start
   * @param model only this model, or empty for every registered model
   */
  public static Result check(
      RatingCatalog catalog, List<ModelOffering> offerings, Instant at, Optional<String> model) {
    List<Gap> gaps = new ArrayList<>();
    List<ModelOffering> checked =
        offerings.stream().filter(o -> model.map(m -> m.equals(o.model())).orElse(true)).toList();
    if (model.isPresent() && checked.isEmpty()) {
      gaps.add(new Gap(model.get(), "", "", "", Reason.NOT_REGISTERED));
    }
    Instant hour = at.truncatedTo(ChronoUnit.HOURS);
    for (ModelOffering o : checked) {
      for (String band : bands(catalog, o.model(), at)) {
        Optional<Sku> sku =
            catalog.skus().stream()
                .filter(s ->
                    s.meter().equals(o.meter())
                        && s.model().equals(o.model())
                        && s.serviceTier().equals(o.serviceTier())
                        && s.contextBand().equals(band)
                        && s.effective().contains(at))
                .findFirst();
        if (sku.isEmpty()) {
          gaps.add(gap(o, band, Reason.NO_SKU));
        } else if (!hasListPrice(catalog, sku.get().skuId(), hour)) {
          gaps.add(gap(o, band, Reason.NO_LIST_PRICE));
        }
      }
    }
    gaps.sort(
        Comparator.comparing(Gap::model)
            .thenComparing(Gap::serviceTier)
            .thenComparing(Gap::meter)
            .thenComparing(Gap::contextBand));
    return new Result(at, catalog.version(), gaps);
  }

  /** The bands a model's usage can resolve to at {@code at}: both bands of its rule, else base. */
  private static Set<String> bands(RatingCatalog catalog, String model, Instant at) {
    Set<String> bands = new LinkedHashSet<>();
    for (ContextBandRule r : catalog.bandRules()) {
      if (r.model().equals(model) && r.effective().contains(at)) {
        bands.add(r.baseBand());
        bands.add(r.aboveBand());
      }
    }
    if (bands.isEmpty()) {
      bands.add(CatalogRatingLibrary.DEFAULT_BAND);
    }
    return bands;
  }

  private static boolean hasListPrice(RatingCatalog catalog, String skuId, Instant hour) {
    for (ListPrice p : catalog.listPrices()) {
      if (p.skuId().equals(skuId) && p.effective().contains(hour)) {
        return true;
      }
    }
    return false;
  }

  private static Gap gap(ModelOffering o, String band, Reason reason) {
    return new Gap(o.model(), o.serviceTier(), o.meter().code(), band, reason);
  }
}
